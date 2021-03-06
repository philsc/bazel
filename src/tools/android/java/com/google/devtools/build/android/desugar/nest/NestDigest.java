// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.android.desugar.nest;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.android.desugar.langmodel.LangModelConstants.NEST_COMPANION_CLASS_SIMPLE_NAME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.devtools.build.android.desugar.io.FileContentProvider;
import com.google.devtools.build.android.desugar.langmodel.ClassAttributeRecord;
import com.google.devtools.build.android.desugar.langmodel.ClassMemberKey;
import com.google.devtools.build.android.desugar.langmodel.ClassMemberRecord;
import com.google.devtools.build.android.desugar.langmodel.ClassName;
import com.google.devtools.build.android.desugar.langmodel.MemberUseKind;
import com.google.devtools.build.android.desugar.langmodel.TypeMappable;
import com.google.devtools.build.android.desugar.langmodel.TypeMapper;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassWriter;

/** Manages the creation and IO stream for nest-companion classes. */
public class NestDigest implements TypeMappable<NestDigest> {

  private final ClassMemberRecord classMemberRecord;
  private final ClassAttributeRecord classAttributeRecord;
  private final Map<ClassName, ClassName> nestCompanionToHostMap;

  /**
   * A map from the class binary names of nest hosts to the associated class writer of the nest's
   * companion.
   */
  private ImmutableMap<ClassName, ClassWriter> companionWriters;

  public static NestDigest create(
      ClassMemberRecord classMemberRecord, ClassAttributeRecord classAttributeRecord) {
    return new NestDigest(
        classMemberRecord, classAttributeRecord, new HashMap<>(), /* companionWriters= */ null);
  }

  private NestDigest(
      ClassMemberRecord classMemberRecord,
      ClassAttributeRecord classAttributeRecord,
      Map<ClassName, ClassName> nestCompanionToHostMap,
      ImmutableMap<ClassName, ClassWriter> companionWriters) {
    this.classMemberRecord = classMemberRecord;
    this.classAttributeRecord = classAttributeRecord;
    this.nestCompanionToHostMap = nestCompanionToHostMap;
    this.companionWriters = companionWriters;
  }

  /**
   * Generates the nest companion class writers. The nest companion classes will be generated as the
   * last placeholder class type for the synthetic constructor, whose originating constructor has
   * any invocation in other classes in nest.
   */
  void prepareCompanionClasses() {
    ImmutableList<ClassName> nestHostsWithCompanion =
        classMemberRecord.findAllConstructorMemberKeys().stream()
            .map(
                constructor ->
                    nestHost(constructor.owner(), classAttributeRecord, nestCompanionToHostMap))
            .flatMap(Streams::stream)
            .distinct()
            .collect(toImmutableList());
    ImmutableMap.Builder<ClassName, ClassWriter> companionWriterBuilders = ImmutableMap.builder();
    for (ClassName nestHost : nestHostsWithCompanion) {
      ClassName nestCompanion = nestHost.innerClass(NEST_COMPANION_CLASS_SIMPLE_NAME);
      nestCompanionToHostMap.put(nestCompanion, nestHost);
      companionWriterBuilders.put(nestHost, new ClassWriter(ClassWriter.COMPUTE_MAXS));
    }
    companionWriters = companionWriterBuilders.build();
  }

  public boolean hasAnyTrackingReason(ClassMemberKey<?> classMemberKey) {
    return classMemberRecord.hasTrackingReason(classMemberKey);
  }

  public boolean hasAnyUse(ClassMemberKey<?> classMemberKey, MemberUseKind useKind) {
    return findAllMemberUseKinds(classMemberKey).contains(useKind);
  }

  public ImmutableList<MemberUseKind> findAllMemberUseKinds(ClassMemberKey<?> classMemberKey) {
    return classMemberRecord.findAllMemberUseKind(classMemberKey);
  }

  /**
   * The public API that finds the nest host for a given class. It is expected {@link
   * #prepareCompanionClasses()} executed before this API is ready. The method returns {@link
   * Optional#empty()} if the class is not part of a nest. A generated nest companion class and its
   * nest host are considered to be a nest host/member relationship.
   */
  public Optional<ClassName> nestHost(ClassName className) {
    // Ensures prepareCompanionClasses has been executed.
    checkNotNull(companionWriters);
    return nestHost(className, classAttributeRecord, nestCompanionToHostMap);
  }

  /**
   * The internal method finds the nest host for a given class from a class attribute record. The
   * method returns {@link * Optional#empty()} if the class is not part of a nest. A generated nest
   * companion class and its * nest host are considered to be a nest host/member relationship.
   *
   * <p>In addition to exam the NestHost_attribute from the class file, this method returns the
   * class under investigation itself for a class with NestMembers_attribute but without
   * NestHost_attribute.
   */
  private static Optional<ClassName> nestHost(
      ClassName className,
      ClassAttributeRecord classAttributeRecord,
      Map<ClassName, ClassName> companionToHostMap) {
    if (companionToHostMap.containsKey(className)) {
      return Optional.of(companionToHostMap.get(className));
    }
    Optional<ClassName> nestHost = classAttributeRecord.getNestHost(className);
    if (nestHost.isPresent()) {
      return nestHost;
    }
    Set<ClassName> nestMembers = classAttributeRecord.getNestMembers(className);
    if (!nestMembers.isEmpty()) {
      return Optional.of(className);
    }
    return Optional.empty();
  }

  /**
   * Returns the internal name of the nest companion class for a given class.
   *
   * <p>e.g. The nest host of a/b/C$D is a/b/C$NestCC
   */
  public ClassName nestCompanion(ClassName className) {
    return nestHost(className)
        .map(nestHost -> nestHost.innerClass(NEST_COMPANION_CLASS_SIMPLE_NAME))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "Expected the presence of NestHost attribute of %s to get nest companion.",
                        className)));
  }

  /**
   * Gets the class visitor of the affiliated nest host of the given class. E.g For the given class
   * com/google/a/b/Delta$Echo, it returns the class visitor of com/google/a/b/Delta$NestCC
   */
  @Nullable
  public ClassWriter getCompanionClassWriter(ClassName className) {
    return nestHost(className).map(nestHost -> companionWriters.get(nestHost)).orElse(null);
  }

  /** Gets all nest companion classes required to be generated. */
  public ImmutableList<String> getAllCompanionClassNames() {
    return getAllCompanionClasses().stream().map(ClassName::binaryName).collect(toImmutableList());
  }

  public ImmutableList<ClassName> getAllCompanionClasses() {
    return companionWriters.keySet().stream().map(this::nestCompanion).collect(toImmutableList());
  }

  /** Gets all nest companion files required to be generated. */
  public ImmutableList<FileContentProvider<ByteArrayInputStream>> getCompanionFileProviders() {
    ImmutableList.Builder<FileContentProvider<ByteArrayInputStream>> fileContents =
        ImmutableList.builder();
    for (ClassName companion : getAllCompanionClasses()) {
      fileContents.add(
          new FileContentProvider<>(
              companion.classFilePathName(),
              () -> getByteArrayInputStreamOfCompanionClass(companion)));
    }
    return fileContents.build();
  }

  private ByteArrayInputStream getByteArrayInputStreamOfCompanionClass(ClassName companion) {
    ClassWriter companionClassWriter = getCompanionClassWriter(companion);
    companionClassWriter.visitEnd();
    checkNotNull(
        companionClassWriter,
        "Expected companion class (%s) to be present in (%s)",
        companionWriters);
    return new ByteArrayInputStream(companionClassWriter.toByteArray());
  }

  @Override
  public NestDigest acceptTypeMapper(TypeMapper typeMapper) {
    return new NestDigest(
        classMemberRecord.acceptTypeMapper(typeMapper),
        classAttributeRecord.acceptTypeMapper(typeMapper),
        typeMapper.mapMutable(nestCompanionToHostMap),
        typeMapper.mapKey(companionWriters));
  }
}
