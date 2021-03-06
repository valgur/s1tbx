package org.jlinda.nest.gpf.coregistration;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GcpDescriptor;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.ResourceUtils;
import org.jlinda.core.Constants;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.coregistration.CPM;
import org.jlinda.core.coregistration.SimpleLUT;

import javax.media.jai.Interpolation;
import javax.media.jai.InterpolationTable;
import javax.media.jai.JAI;
import javax.media.jai.RenderedOp;
import javax.media.jai.WarpPolynomial;
import java.awt.Desktop;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@OperatorMetadata(alias = "Resample",
        category = "Radar/Coregistration",
        authors = "Petar Marinkovic (with contributions of Jun Lu, Luis Veci)",
        copyright = "PPO.labs and European Space Agency",
        description = "Estimate coregistration polynomial and resample slave images")
public class ResampleOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The order of coregistration polynomial",
            valueSet = {"1", "2", "3"},
            defaultValue = "2",
            label = "Coregistration Polynomial Order")
    private int cpmDegree = 2;

    @Parameter(description ="Interpolation kernel used in data resampling",
            valueSet = {TRI, CC4P, CC6P, TS6P, TS8P, TS16P},
            defaultValue = CC6P,
            label = "Interpolation Kernel")
    private String cpmInterpKernel = CC6P;


    @Parameter(description = "Maximum number of outlier iterations in estimation of polynomial",
            interval = "(1, *)",
            defaultValue = "20",
            label = "Number of Outlier Removal Iterations")
    private int cpmMaxIterations = 20;

    @Parameter(description = "Confidence level for outlier detection procedure, lower value accepts more outliers",
            valueSet = {"0.001", "0.005", "0.05", "0.1"},
            defaultValue = "0.05",
            label = "Significance Level for Outlier Removal")
    private String cpmAlphaValue = "0.05";
    private float cpmWtestCriticalValue;

    @Parameter(description = "Refine estimated offsets using a-priori DEM",
            defaultValue = "false",
            label = "Offset Refinement Based on DEM")
    private Boolean cpmDemRefinement = false;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec", 
            label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(description = "Show the Residuals file in a text viewer",
            defaultValue = "false",
            label = "Show Residuals")
    private Boolean openResidualsFile = false;

    // band fields
    private Band masterBand = null;
    private Band masterBand2 = null;
    private String[] masterBandNames = null;
    private String processedSlaveBand;

    // maps
    private final Map<Band, Band> sourceRasterMap = new HashMap<Band, Band>(10);
    private final Map<Band, Band> complexSrcMap = new HashMap<Band, Band>(10);
    private final Map<Band, CPM> cpmMap = new HashMap<Band, CPM>(10);

    // processing control flags
    private boolean complexCoregistration = false;
    private boolean cpmAvailable = false;

    // interpolation kernel fields
    private Interpolation interp = null;
    private InterpolationTable interpTable = null;
    public static final String TRI = SimpleLUT.TRI;
    public static final String CC4P = SimpleLUT.CC4P;
    public static final String CC6P = SimpleLUT.CC6P;
    public static final String TS6P = SimpleLUT.TS6P;
    public static final String TS8P = SimpleLUT.TS8P;
    public static final String TS16P = SimpleLUT.TS16P;

    // DEM refinement
    private static final int ORBIT_INTERP_DEGREE = 3;
    float demNoDataValue = 0;
    private ElevationModel dem = null;

    private boolean excludeMaster = false;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public ResampleOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {
        try {

            // clear any old residual file
            final File residualsFile = getResidualsFile(sourceProduct);
            if (residualsFile.exists()) {
                residualsFile.delete();
            }

            getMasterBands();

            // Set parameter for outlier removal: precomputed in matlab - see pretest.m
            //   alpha0 = 0.05;
            //   k1 = sqrt(chi2inv(1.0-alpha0,1))
            //   k1 = 1.9599....
            //   ....or
            //   norminv(1-alpha2/2)
            switch (cpmAlphaValue) {
                case "0.001":
                    cpmWtestCriticalValue = 3.2905267314919f;
                    break;
                case "0.05":
                    cpmWtestCriticalValue = 1.95996398454005f;
                    break;
                case "0.1":
                    cpmWtestCriticalValue = 1.64485362695147f;
                    break;
                default:
                    cpmWtestCriticalValue = 1.95996398454005f;
            }

            // Construct interpolation LUT
            switch (cpmInterpKernel) {
                case CC4P:
                    constructInterpTable(CC4P);
                    break;
                case CC6P:
                    constructInterpTable(CC6P);
                    break;
                case TS6P:
                    constructInterpTable(TS6P);
                    break;
                case TS8P:
                    constructInterpTable(TS8P);
                    break;
                case TS16P:
                    constructInterpTable(TS16P);
                    break;
                default:
                    interp = Interpolation.getInstance(Interpolation.INTERP_NEAREST);
                    break;
            }

            createTargetProduct();

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            processedSlaveBand = absRoot.getAttributeString("processed_slave");

        } catch (Throwable e) {
            openResidualsFile = true;
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getMasterBands() {
        String mstBandName = sourceProduct.getBandAt(0).getName();

        // find co-pol bands
        final String[] masterBandNames = StackUtils.getMasterBandNames(sourceProduct);
        for(String bandName : masterBandNames) {
            final String mstPol = OperatorUtils.getPolarizationFromBandName(bandName);
            if(mstPol != null && (mstPol.equals("hh") || mstPol.equals("vv"))) {
                mstBandName = bandName;
                break;
            }
        }
        masterBand = sourceProduct.getBand(mstBandName);
        if (masterBand.getUnit() != null && masterBand.getUnit().equals(Unit.REAL)) {
            int mstIdx = sourceProduct.getBandIndex(mstBandName);
            if(sourceProduct.getNumBands() > mstIdx + 1) {
                masterBand2 = sourceProduct.getBandAt(mstIdx + 1);
                complexCoregistration = true;
            }
        }
    }

    private String formatName(final Band srcBand) {
        String name = srcBand.getName();
        if(excludeMaster) {  // multi-output without master
            String newName = StackUtils.getBandNameWithoutDate(name);
            if(name.equals(processedSlaveBand)) {
                processedSlaveBand = newName;
            }
            return newName;
        }
        return name;
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        masterBandNames = StackUtils.getMasterBandNames(sourceProduct);

        final int numSrcBands = sourceProduct.getNumBands();
        int inc = 2;

        for (int i = 0; i < numSrcBands; i += inc) {

            // for I-real bands
            final Band srcBand = sourceProduct.getBandAt(i);
            Band targetBand;
            if (srcBand == masterBand || srcBand == masterBand2 ||
                    StringUtils.contains(masterBandNames, srcBand.getName())) {
                targetBand = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, false);
                targetBand.setSourceImage(srcBand.getSourceImage());
            } else {
                targetBand = targetProduct.addBand(formatName(srcBand), ProductData.TYPE_FLOAT32);
                ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
            }
            sourceRasterMap.put(targetBand, srcBand);

            // for Q-imag bands
            final Band srcBandQ = sourceProduct.getBandAt(i + 1);
            Band targetBandQ;
            if (srcBand == masterBand || srcBand == masterBand2 ||
                    StringUtils.contains(masterBandNames, srcBand.getName())) {
                targetBandQ = ProductUtils.copyBand(srcBandQ.getName(), sourceProduct, targetProduct, false);
                targetBandQ.setSourceImage(srcBandQ.getSourceImage());
            } else {
                targetBandQ = targetProduct.addBand(formatName(srcBandQ), ProductData.TYPE_FLOAT32);
                ProductUtils.copyRasterDataNodeProperties(srcBandQ, targetBandQ);
            }

            // put bands in maps
            sourceRasterMap.put(targetBandQ, srcBandQ);
            complexSrcMap.put(srcBandQ, srcBand);

            // create product suffix
            final String suffix = '_'+OperatorUtils.getSuffixFromBandName(srcBand.getName());

            // virtual bands
            ReaderUtils.createVirtualIntensityBand(targetProduct, targetBand, targetBandQ, suffix);
        }

        // coregistrated image should have the same geo-coding as the master image
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
        updateTargetProductMetadata();
    }

    /**
     * Update metadata in the target product.
     */
    private void updateTargetProductMetadata() {
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.coregistered_stack, 1);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetRectangle = targetTile.getRectangle();
        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        //System.out.println("WARPOperator: x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        try {

            // get & compute CPM
            if (!cpmAvailable) {
                if (cpmDemRefinement) {
                    createDEM();
                }
                // Most likely not needed
                computeCPM(targetRectangle);
            }

            // get source bands
            final Band srcBand = sourceRasterMap.get(targetBand);
            if (srcBand == null)
                return;
            Band realSrcBand = complexSrcMap.get(srcBand);
            if (realSrcBand == null)
                realSrcBand = srcBand;

            // create source image
            final Tile sourceRaster = getSourceTile(srcBand, targetRectangle);

            if (pm.isCanceled())
                return;

            // pull CPM from map for source bands
            final CPM cpmData = cpmMap.get(realSrcBand);
            if (cpmData.noRedundancy)
                return;

            // get source image
            final RenderedImage srcImage = sourceRaster.getRasterDataNode().getSourceImage();

            // resample source image -> target image
            final RenderedOp warpedImage = createWarpImage(cpmData.jaiWarp, srcImage);

            // copy warped image data to target
            final float[] dataArray = warpedImage.getData(targetRectangle).getSamples(x0, y0, w, h, 0, (float[]) null);

            targetTile.setRawSamples(ProductData.createInstance(dataArray));

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private synchronized void createDEM() throws IOException {

        final Resampling resampling = ResamplingFactory.createResampling(ResamplingFactory.BILINEAR_INTERPOLATION_NAME);

        if (dem != null) return;

        final ElevationModelRegistry elevationModelRegistry = ElevationModelRegistry.getInstance();
        final ElevationModelDescriptor demDescriptor = elevationModelRegistry.getDescriptor(demName);

        if (demDescriptor == null) {
            throw new OperatorException("The DEM '" + demName + "' is not supported.");
        }

        dem = demDescriptor.createDem(resampling);
        if (dem == null) {
            throw new OperatorException("The DEM '" + demName + "' has not been installed.");
        }

        demNoDataValue = demDescriptor.getNoDataValue();

    }


    // CPM: Coregistration PolynoMial
    private synchronized void computeCPM(final Rectangle targetRectangle) throws Exception {
        
        if (cpmAvailable) {
            return;
        }

        // find first real slave band
        final Band targetBand = targetProduct.getBand(processedSlaveBand);
        // force getSourceTile to computeTiles on GCPSelection
        final Tile sourceRaster = getSourceTile(sourceRasterMap.get(targetBand), targetRectangle);

        // for all slave band pairs compute a CPM
        final int numSrcBands = sourceProduct.getNumBands();
        int inc = 2;

        final ProductNodeGroup<Placemark> masterGCPGroup = GCPManager.instance().getGcpGroup(masterBand);
        final Window masterWindow = new Window(0, sourceProduct.getSceneRasterHeight(), 0, sourceProduct.getSceneRasterWidth());

        // setup master metadata
        SLCImage masterMeta = null;
        Orbit masterOrbit = null;
        if (cpmDemRefinement) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            masterMeta = new SLCImage(absRoot);
            masterOrbit = new Orbit(absRoot,ORBIT_INTERP_DEGREE);
        }

        boolean appendFlag = false;
        int slaveMetaCnt = 0;
        for (int i = 0; i < numSrcBands; i += inc) {

            final Band srcBand = sourceProduct.getBandAt(i);
            if (srcBand == masterBand || srcBand == masterBand2 ||
                    StringUtils.contains(masterBandNames, srcBand.getName()))
                continue;

            ProductNodeGroup<Placemark> slaveGCPGroup = GCPManager.instance().getGcpGroup(srcBand);
            if (slaveGCPGroup.getNodeCount() < 3) {
                // find others for same slave product
                final String slvProductName = StackUtils.getSlaveProductName(sourceProduct, srcBand, null);
                for(Band band : sourceProduct.getBands()) {
                    if(band != srcBand) {
                        final String productName = StackUtils.getSlaveProductName(sourceProduct, band, null);
                        if(slvProductName != null && slvProductName.equals(productName)) {
                            slaveGCPGroup = GCPManager.instance().getGcpGroup(band);
                            if (slaveGCPGroup.getNodeCount() >= 3)
                                break;
                        }
                    }
                }
            }


            final CPM cpm = new CPM(cpmDegree, cpmMaxIterations, cpmWtestCriticalValue, masterWindow, masterGCPGroup, slaveGCPGroup);
            cpmMap.put(srcBand, cpm);

            final int nodeCount = slaveGCPGroup.getNodeCount();
            if (nodeCount < 3) {
                cpm.noRedundancy = true;
                continue;
            }

            // setup slave metadata
            if (cpmDemRefinement && !cpm.noRedundancy) {

                // get height for corresponding points
                double[] heightArray = new double[nodeCount];
                final java.util.List<Placemark> slaveGCPList = new ArrayList<>();

                for (int j = 0; j < nodeCount; j++) {

                    // work only with windows that survived threshold for this slave
                    slaveGCPList.add(slaveGCPGroup.get(j));
                    final Placemark sPin = slaveGCPList.get(j);
                    final Placemark mPin = masterGCPGroup.get(sPin.getName());
                    final PixelPos mGCPPos = mPin.getPixelPos();

                    double[] phiLamPoint = masterOrbit.lph2ell(mGCPPos.y, mGCPPos.x, 0, masterMeta);
                    PixelPos demIndexPoint = dem.getIndex(new GeoPos((phiLamPoint[0] * Constants.RTOD), (phiLamPoint[1] * Constants.RTOD)));
                    
                    double height = dem.getSample(demIndexPoint.x, demIndexPoint.y);

                    if (Double.isNaN(height) || height == demNoDataValue) {
                        height = demNoDataValue;
                    }

                    heightArray[j] = height;

                }

                final MetadataElement slaveRoot = targetProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT).getElementAt(slaveMetaCnt);
                final SLCImage slaveMeta = new SLCImage(slaveRoot);
                final Orbit slaveOrbit = new Orbit(slaveRoot, ORBIT_INTERP_DEGREE);
                cpm.setDemNoDataValue(demNoDataValue);
                cpm.setUpDEMRefinement(masterMeta, masterOrbit, slaveMeta, slaveOrbit, heightArray);
                cpm.setUpDemOffset();
            }

            cpm.computeCPM();
            cpm.computeEstimationStats();
            cpm.wrapJaiWarpPolynomial();

            if(cpm.noRedundancy) {
                continue;
            }

            if (!appendFlag) {
                appendFlag = true;
            }

            outputCoRegistrationInfo(sourceProduct, cpm, appendFlag, srcBand.getName());

            addSurvivedSlaveGCPs(cpm, srcBand.getName());
        }

        announceGCPWarning();

        GCPManager.instance().removeAllGcpGroups();

        if (openResidualsFile) {
            final File residualsFile = getResidualsFile(sourceProduct);
            if (Desktop.isDesktopSupported() && residualsFile.exists()) {
                try {
                    Desktop.getDesktop().open(residualsFile);
                } catch (Exception e) {
                    System.out.println("Error opening residuals file " + e.getMessage());
                    // do nothing
                }
            }
        }

        // update metadata
        writeCPMToMetadata();

        cpmAvailable = true;
    }


    private void constructInterpTable(String interpolationMethod) {

        // construct interpolation LUT
        SimpleLUT lut = new SimpleLUT(interpolationMethod);
        lut.constructLUT();

        int kernelLength = lut.getKernelLength();

        // get LUT and cast it to float for JAI
        double[] lutArrayDoubles = lut.getKernelAsArray();
        float lutArrayFloats[] = new float[lutArrayDoubles.length];
        int i = 0;
        for (double lutElement : lutArrayDoubles) {
            lutArrayFloats[i++] = (float) lutElement;
        }

        // construct interpolation table for JAI resampling
        final int subsampleBits = 7;
        final int precisionBits = 32;
        int padding = kernelLength / 2 - 1;

        interpTable = new InterpolationTable(padding, kernelLength, subsampleBits, precisionBits, lutArrayFloats);
    }

    private void addSurvivedSlaveGCPs(final CPM cpmData, final String bandName) {

        final GeoCoding targetGeoCoding = targetProduct.getSceneGeoCoding();
        final ProductNodeGroup<Placemark> targetGCPGroup = GCPManager.instance().getGcpGroup(targetProduct.getBand(bandName));
        targetGCPGroup.removeAll();

        for (int i = 0; i < cpmData.slaveGCPList.size(); ++i) {
            final Placemark sPin = cpmData.slaveGCPList.get(i);
            final Placemark tPin = Placemark.createPointPlacemark(GcpDescriptor.getInstance(),
                    sPin.getName(),
                    sPin.getLabel(),
                    sPin.getDescription(),
                    sPin.getPixelPos(),
                    sPin.getGeoPos(),
                    targetGeoCoding);

            targetGCPGroup.add(tPin);
        }
    }

    private void writeCPMToMetadata() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final Set<Band> bandSet = cpmMap.keySet();

        for(Band band : bandSet) {
            final MetadataElement bandElem = AbstractMetadata.getBandAbsMetadata(absRoot, band.getName(), true);

            MetadataElement warpDataElem = bandElem.getElement("WarpData");
            if(warpDataElem == null) {
                warpDataElem = new MetadataElement("WarpData");
                bandElem.addElement(warpDataElem);
            } else {
                // empty out element
                final MetadataAttribute[] attribList = warpDataElem.getAttributes();
                for(MetadataAttribute attrib : attribList) {
                    warpDataElem.removeAttribute(attrib);
                }
            }

            final CPM cpmData = cpmMap.get(band);
            if(cpmData.numObservations > 0) {
                for (int i = 0; i < cpmData.numObservations; i++) {
                    final MetadataElement gcpElem = new MetadataElement("GCP"+i);
                    warpDataElem.addElement(gcpElem);

                    gcpElem.setAttributeDouble("mst_x", cpmData.xMaster.get(i));
                    gcpElem.setAttributeDouble("mst_y", cpmData.yMaster.get(i));

                    gcpElem.setAttributeDouble("slv_x", cpmData.xSlave.get(i));
                    gcpElem.setAttributeDouble("slv_y", cpmData.ySlave.get(i));

                    if (!cpmData.noRedundancy) {
                        gcpElem.setAttributeDouble("rms", cpmData.rms.get(i));
                    }
                }
            }
        }
    }

    private static File getResidualsFile(final Product sourceProduct) {
        final String fileName = sourceProduct.getName() + "_residual.txt";
        return new File(ResourceUtils.getReportFolder(), fileName);
    }

    /**
     * Create warped image.
     *
     * @param warp     The WARP polynomial.
     * @param srcImage The source image.
     * @return The warped image.
     */
    private RenderedOp createWarpImage(WarpPolynomial warp, final RenderedImage srcImage) {

        // reformat source image by casting pixel values from ushort to float
        final ParameterBlock pb1 = new ParameterBlock();
        pb1.addSource(srcImage);
        pb1.add(DataBuffer.TYPE_FLOAT);
        final RenderedImage srcImageFloat = JAI.create("format", pb1);

        // get warped image
        final ParameterBlock pb2 = new ParameterBlock();
        pb2.addSource(srcImageFloat);
        pb2.add(warp);

        if (interp != null) {
            pb2.add(interp);
        } else if (interpTable != null) {
            pb2.add(interpTable);
        }

        return JAI.create("warp", pb2);
    }

    private void announceGCPWarning() {
        String msg = "";
        for(Band srcBand : sourceProduct.getBands()) {
            final CPM cpmData = cpmMap.get(srcBand);
            if(cpmData != null && cpmData.noRedundancy) {
                msg += srcBand.getName() +" does not have enough valid GCPs for the warp\n";
                openResidualsFile = true;
            }
        }
        if(!msg.isEmpty()) {
            System.out.println(msg);
            //if(VisatApp.getApp() != null) {
            //    VisatApp.getApp().showWarningDialog("Some bands did not coregister", msg);
            //}
        }
    }

    /**
     * Output co-registration information to file.
     *
     * @param sourceProduct      The source product.
     * @param cpm                Stores the coregistration polynomial (cpm) information per band.
     * @param appendFlag         Boolean flag indicating if the information is output to file in appending mode.
     * @param bandName           The band name
     * @throws OperatorException The exception.
     * @throws FileNotFoundException The exception.
     */
    public static void outputCoRegistrationInfo(final Product sourceProduct,
                                                final CPM cpm,
                                                final boolean appendFlag,
                                                final String bandName) throws OperatorException, FileNotFoundException {

        final File residualFile = getResidualsFile(sourceProduct);
        PrintStream p = null; // declare a print stream object

        try {
            final FileOutputStream out = new FileOutputStream(residualFile.getAbsolutePath(), appendFlag);

            // Connect print stream to the output stream
            p = new PrintStream(out);

            p.println();

            if (!appendFlag) {
                p.println();
                p.format("Transformation degree = %d", cpm.cpmDegree);
                p.println();
            }

            p.println();
            p.print("------------------------ Band: " + bandName + " (Parse " + cpm.numIterations + ") ------------------------");
            p.println();

            if (!cpm.noRedundancy) {
                p.println();
                p.println("WARP coefficients:");
                for (double xCoeff : cpm.xCoefJai) {
                    p.print((float) xCoeff + ", ");
                }

                p.println();
                for (double yCoeff : cpm.yCoefJai) {
                    p.print((float) yCoeff + ", ");
                }
                p.println();
            }

            if (appendFlag) {
                p.println();
                p.format("W-test critical value: %5.3f", cpm.criticalValue);
                p.println();
            }

            if (appendFlag) {
                p.println();
                p.format("Geometry based offset refinement: %B", cpm.demRefinement);
                p.println();
            }

            p.println();
            if (appendFlag) {
                p.print("Valid GCPs after parse " + cpm.numIterations + " :");
            } else {
                p.print("Initial Valid GCPs:");
            }
            p.println();

            if (!cpm.noRedundancy) {

                p.println();
                p.println("No. | Master GCP x | Master GCP y | Slave GCP x |" +
                        " Slave GCP y | Row Residual | Col Residual |    RMS    |");
                p.println("-------------------------------------------------" +
                        "--------------------------------------------------------");
                for (int i = 0; i < cpm.numObservations; i++) {
                    p.format("%2d  |%13.3f |%13.3f |%12.3f |%12.3f |%13.3f |%13.3f |%10.3f |",
                            i, cpm.xMaster.get(i), cpm.yMaster.get(i),
                            cpm.xSlave.get(i), cpm.ySlave.get(i),
                            cpm.xOffset.get(i), cpm.yOffset.get(i), cpm.rms.get(i));
                    p.println();
                }

                p.println();
                p.print("Row residual mean = " + cpm.yErrorMean);
                p.println();
                p.print("Row residual std = " + cpm.yErrorStd);
                p.println();

                p.println();
                p.print("Col residual mean = " + cpm.xErrorMean);
                p.println();
                p.print("Col residual std = " + cpm.xErrorStd);
                p.println();

                p.println();
                p.print("RMS mean = " + cpm.rmsMean);
                p.println();
                p.print("RMS std = " + cpm.rmsStd);
                p.println();

            } else {

                p.println();
                p.println("No. | Master GCP x | Master GCP y | Slave GCP x | Slave GCP y |");
                p.println("---------------------------------------------------------------");
                for (int i = 0; i < cpm.numObservations; i++) {
                    p.format("%2d  |%13.3f |%13.3f |%12.3f |%12.3f |",
                            i, cpm.xMaster.get(2), cpm.yMaster.get(i),
                            cpm.xSlave.get(i), cpm.ySlave.get(i));
                    p.println();
                }
            }
            p.println();
            p.println();

        } catch (IOException exc) {
            throw new OperatorException(exc);
        } finally {
            if (p != null)
                p.close();
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ResampleOp.class);
        }
    }

}

