/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.test.rule.TestRunnerSpec;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import java.util.Map;

/**
 * Coerces a freeform JSON as a {@link TestRunnerSpec}, which is basically a JSON containing {@link
 * StringWithMacros}
 */
public class TestRunnerSpecCoercer implements TypeCoercer<Object, TestRunnerSpec> {

  private final TypeCoercer<UnconfiguredStringWithMacros, StringWithMacros> macrosTypeCoercer;
  private final TypeCoercer<
          ImmutableMap<UnconfiguredStringWithMacros, Object>,
          ImmutableMap<StringWithMacros, TestRunnerSpec>>
      mapTypeCoercer;
  private final ListTypeCoercer<Object, TestRunnerSpec> listTypeCoercer;

  public TestRunnerSpecCoercer(
      TypeCoercer<UnconfiguredStringWithMacros, StringWithMacros> macrosTypeCoercer) {
    this.macrosTypeCoercer = macrosTypeCoercer;
    this.mapTypeCoercer = new MapTypeCoercer<>(macrosTypeCoercer, this);
    this.listTypeCoercer = new ListTypeCoercer<>(this);
  }

  @Override
  public TypeToken<TestRunnerSpec> getOutputType() {
    return TypeToken.of(TestRunnerSpec.class);
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return false;
  }

  @Override
  public void traverse(CellNameResolver cellRoots, TestRunnerSpec object, Traversal traversal) {}

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    // TODO(nga): make it 2p
    return object;
  }

  @Override
  public TestRunnerSpec coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {

    if (object instanceof Map) {
      return TestRunnerSpec.ofMap(
          mapTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object));
    }
    if (object instanceof Iterable) {
      return TestRunnerSpec.ofList(
          listTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object));
    }
    if (object instanceof Number) {
      return TestRunnerSpec.ofNumber((Number) object);
    } else if (object instanceof Boolean) {
      return TestRunnerSpec.ofBoolean((Boolean) object);
    } else {
      return TestRunnerSpec.ofStringWithMacros(
          macrosTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              object));
    }
  }
}
