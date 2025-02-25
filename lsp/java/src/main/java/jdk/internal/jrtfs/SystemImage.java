/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.jrtfs;

import com.itsaky.androidide.utils.Environment;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;

import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReader.Node;

/**
 * @implNote This class needs to maintain JDK 8 source compatibility.
 *     <p>It is used internally in the JDK to implement jimage/jrtfs access, but also compiled and
 *     delivered as part of the jrtfs.jar to support access to the jimage file provided by the
 *     shipped JDK by tools running on JDK 8.
 */
abstract class SystemImage {

    abstract Node findNode(String path) throws IOException;

    abstract byte[] getResource(Node node) throws IOException;

    abstract void close() throws IOException;

    static SystemImage open() throws IOException {

        // Initialize the fields
        // Make sure that they are up to date with com.itsaky.androidide.utils.Environment
        init();

        if (modulesImageExists) {
            // open a .jimage and build directory structure
            final ImageReader image = ImageReader.open(moduleImageFile);
            image.getRootDirectory();
            return new SystemImage() {
                @Override
                Node findNode(String path) throws IOException {
                    return image.findNode(path);
                }

                @Override
                byte[] getResource(Node node) throws IOException {
                    return image.getResource(node);
                }

                @Override
                void close() throws IOException {
                    image.close();
                }
            };
        }
        if (Files.notExists(explodedModulesDir))
            throw new FileSystemNotFoundException(explodedModulesDir.toString());
        return new ExplodedImage(explodedModulesDir);
    }

    static String RUNTIME_HOME;
    // "modules" jimage file Path
    static Path moduleImageFile;
    // "modules" jimage exists or not?
    static boolean modulesImageExists;
    // <JAVA_HOME>/modules directory Path
    static Path explodedModulesDir;

    static {
        init();
    }

    public static void init() {
        PrivilegedAction<String> pa = SystemImage::findHome;
        RUNTIME_HOME = AccessController.doPrivileged(pa);

        FileSystem fs = FileSystems.getDefault();
        moduleImageFile = fs.getPath(RUNTIME_HOME, "lib", "modules");
        explodedModulesDir = fs.getPath(RUNTIME_HOME, "modules");

        modulesImageExists =
                AccessController.doPrivileged(
                        (PrivilegedAction<Boolean>) () -> Files.isRegularFile(moduleImageFile));
    }

    /**
     * Returns the appropriate JDK home for this usage of the FileSystemProvider. When the
     * CodeSource is null (null loader) then jrt:/ is the current runtime, otherwise the JDK home is
     * located relative to jrt-fs.jar.
     */
    private static String findHome() {
        return Environment.COMPILER_MODULE.getAbsolutePath();
    }
}
