/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.io.hdf;

import org.esa.s1tbx.io.netcdf.NetCDFReaderPlugIn;
import org.esa.snap.core.dataio.DecodeQualification;

/**
 * The ReaderPlugIn for HDF products.
 */
public class HDFReaderPlugIn extends NetCDFReaderPlugIn {

    private final static String[] HDF_FORMAT_NAMES = {"HDF"};
    private final static String[] HDF_FORMAT_FILE_EXTENSIONS = {"hdf", "h5", "h4", "h5eos"};
    private final static String HDF_PLUGIN_DESCRIPTION = "HDF Products";

    public HDFReaderPlugIn() {
        FORMAT_NAMES = HDF_FORMAT_NAMES;
        FORMAT_FILE_EXTENSIONS = HDF_FORMAT_FILE_EXTENSIONS;
        PLUGIN_DESCRIPTION = HDF_PLUGIN_DESCRIPTION;
    }

    protected DecodeQualification isIntended(final String extension) {
        return DecodeQualification.SUITABLE;
    }
}
