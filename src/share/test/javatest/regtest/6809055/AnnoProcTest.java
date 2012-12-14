/*
 * Copyright 2009 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * @test
 * @build AnnoProcTest
 * @compile -processor AnnoProcTest AnnoProcTest.java
 */

import java.io.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
import javax.tools.*;

@SupportedSourceVersion(SourceVersion.RELEASE_6)
@SupportedAnnotationTypes("*")
public class AnnoProcTest extends AbstractProcessor {
    public boolean process(Set<? extends TypeElement> elems, RoundEnvironment rEnv) {
        if (rEnv.processingOver()) // ignore final round
            return true;

        messager = processingEnv.getMessager();
        System.err.println("Properties: " + System.getProperties());

        test("test.src", "AnnoProcTest.java");
        test("test.classes", "AnnoProcTest.class");

        return true;
    }

    void test(String prop, String file) {
        String v = System.getProperty(prop);
        if (v == null) {
            messager.printMessage(Diagnostic.Kind.ERROR, prop + " not set");
        } else {
            File f = new File(v, file);
            if (f.exists()) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    prop + "=" + v + "\nfile=" + f + "\nfile size=" + f.length());
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "expected file not found with " + prop + "\n" + prop + "=" + v + "\nfile=" + f);
            }
        }
    }

    private Messager messager;
}