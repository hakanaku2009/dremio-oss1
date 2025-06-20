/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.compile;

import com.dremio.exec.compile.ClassTransformer.ClassNames;
import com.dremio.exec.exception.ClassTransformationException;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.IClassLoader;
import org.codehaus.janino.Java;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.codehaus.janino.UnitCompiler;
import org.codehaus.janino.util.ClassFile;

public class JaninoClassCompiler extends AbstractClassCompiler {
  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(JaninoClassCompiler.class);

  private IClassLoader compilationClassLoader;

  public JaninoClassCompiler() {
    this(Thread.currentThread().getContextClassLoader());
  }

  @VisibleForTesting
  JaninoClassCompiler(ClassLoader parentClassLoader) {
    this.compilationClassLoader = new ClassLoaderIClassLoader(parentClassLoader);
  }

  @Override
  protected ClassBytes[] getByteCode(
      final ClassNames className, final String sourcecode, boolean debug)
      throws CompileException, IOException, ClassNotFoundException, ClassTransformationException {
    StringReader reader = new StringReader(sourcecode);
    Scanner scanner = new Scanner((String) null, reader);
    Java.AbstractCompilationUnit compilationUnit =
        new Parser(scanner).parseAbstractCompilationUnit();
    List<ClassFile> classFiles = new LinkedList<>();
    new UnitCompiler(compilationUnit, compilationClassLoader)
        .compileUnit(debug, debug, debug, classFiles);
    return classFiles.stream()
        .map(file -> new ClassBytes(file.getThisClassName(), file.toByteArray()))
        .toArray(ClassBytes[]::new);
  }

  @Override
  protected org.slf4j.Logger getLogger() {
    return logger;
  }
}
