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
package org.esa.s1tbx.io.orbits;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.dataop.downloadable.ftpUtils;
import org.esa.snap.engine_utilities.datamodel.Orbits;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Base class for Orbit files
 */
public abstract class BaseOrbitFile implements OrbitFile {
    protected final String orbitType;
    protected final MetadataElement absRoot;
    protected File orbitFile = null;

    protected ftpUtils ftp = null;
    protected Map<String, Long> fileSizeMap = null;

    protected BaseOrbitFile(final String orbitType, final MetadataElement absRoot) {
        this.orbitType = orbitType;
        this.absRoot = absRoot;
    }

    public abstract File retrieveOrbitFile() throws Exception;

    public abstract Orbits.OrbitVector getOrbitData(final double utc) throws Exception;

    public File getOrbitFile() {
        return orbitFile;
    }

    protected static void getRemoteFiles(final ftpUtils ftp, final Map<String, Long> fileSizeMap,
                                         final String remotePath, final File localPath, final ProgressMonitor pm) {
        final Set<String> remoteFileNames = fileSizeMap.keySet();
        pm.beginTask("Downloading Orbit files from " + remotePath, remoteFileNames.size());
        for (String fileName : remoteFileNames) {
            if (pm.isCanceled()) break;

            final long fileSize = fileSizeMap.get(fileName);
            final File localFile = new File(localPath, fileName);
            if (localFile.exists() && localFile.length() == fileSize)
                continue;
            try {
                int attempts = 0;
                while (attempts < 3) {
                    final ftpUtils.FTPError result = ftp.retrieveFile(remotePath + '/' + fileName, localFile, fileSize);
                    if (result == ftpUtils.FTPError.OK) {
                        break;
                    } else {
                        attempts++;
                        localFile.delete();
                    }
                }
            } catch (Exception e) {
                localFile.delete();
                System.out.println(e.getMessage());
            }

            pm.worked(1);
        }
        pm.done();
    }

    protected boolean getRemoteFile(String remoteFTP, String remotePath, File localFile) {
        try {
            if (ftp == null) {
                ftp = new ftpUtils(remoteFTP);
                fileSizeMap = ftpUtils.readRemoteFileList(ftp, remoteFTP, remotePath);
            }

            final String remoteFileName = localFile.getName();
            final Long fileSize = fileSizeMap.get(remoteFileName);

            final ftpUtils.FTPError result = ftp.retrieveFile(remotePath + remoteFileName, localFile, fileSize);
            if (result == ftpUtils.FTPError.OK) {
                return true;
            } else {
                localFile.delete();
            }

            return false;
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return false;
    }
}
