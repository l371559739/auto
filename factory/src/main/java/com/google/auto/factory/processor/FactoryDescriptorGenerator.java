/*
 * Copyright (C) 2013 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.factory.processor;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.tools.Diagnostic.Kind.ERROR;

import javax.annotation.processing.Messager;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementKindVisitor6;
import javax.lang.model.util.Elements;

import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;

/**
 * A service that traverses an element and returns the set of factory methods defined therein.
 *
 * @author Gregory Kick
 */
final class FactoryDescriptorGenerator {
  private final Messager messager;
  private final Elements elements;
  private final AutoFactoryDeclaration.Factory declarationFactory;

  @Inject FactoryDescriptorGenerator(Messager messager, Elements elements,
      AutoFactoryDeclaration.Factory declarationFactory) {
    this.messager = messager;
    this.elements = elements;
    this.declarationFactory = declarationFactory;
  }

  ImmutableSet<FactoryMethodDescriptor> generateDescriptor(Element element) {
    final AnnotationMirror mirror = Mirrors.getAnnotationMirror(element, AutoFactory.class).get();
    final Optional<AutoFactoryDeclaration> declaration = declarationFactory.createIfValid(element);
    if (!declaration.isPresent()) {
      return ImmutableSet.of();
    }
    return element.accept(new ElementKindVisitor6<ImmutableSet<FactoryMethodDescriptor>, Void>() {
      @Override
      protected ImmutableSet<FactoryMethodDescriptor> defaultAction(Element e, Void p) {
        throw new AssertionError("@AutoFactory applied to an impossible element");
      }

      @Override
      public ImmutableSet<FactoryMethodDescriptor> visitTypeAsClass(TypeElement type, Void p) {
        if (type.getModifiers().contains(ABSTRACT)) {
          // applied to an abstract factory
          messager.printMessage(ERROR,
              "Auto-factory doesn't support being applied to abstract classes.", type, mirror);
          return ImmutableSet.of();
        } else {
          // applied to the type to be created
          ImmutableSet<ExecutableElement> constructors = Elements2.getConstructors(type);
          if (constructors.isEmpty()) {
            return generateDescriptorForDefaultConstructor(declaration.get(), type);
          } else {
            return FluentIterable.from(constructors)
                .transform(new Function<ExecutableElement, FactoryMethodDescriptor>() {
                  @Override public FactoryMethodDescriptor apply(ExecutableElement constructor) {
                    return generateDescriptorForConstructor(declaration.get(), constructor);
                  }
                })
                .toSet();
          }
        }
      }

      @Override
      public ImmutableSet<FactoryMethodDescriptor> visitTypeAsInterface(TypeElement type, Void p) {
        // applied to the factory interface
        messager.printMessage(ERROR,
            "Auto-factory doesn't support being applied to interfaces.", type, mirror);
        return ImmutableSet.of();
      }

      @Override
      public ImmutableSet<FactoryMethodDescriptor> visitExecutableAsConstructor(ExecutableElement e,
          Void p) {
        // applied to a constructor of a type to be created
        return ImmutableSet.of(generateDescriptorForConstructor(declaration.get(), e));
      }
    }, null);
  }

  FactoryMethodDescriptor generateDescriptorForConstructor(final AutoFactoryDeclaration declaration,
      ExecutableElement constructor) {
    checkNotNull(constructor);
    checkArgument(constructor.getKind() == ElementKind.CONSTRUCTOR);
    Element classElement = constructor.getEnclosingElement();
    Name returnType = classElement.accept(
        new ElementKindVisitor6<Name, Void>() {
          @Override
          protected Name defaultAction(Element e, Void p) {
            throw new AssertionError();
          }

          @Override
          public Name visitTypeAsClass(TypeElement e, Void p) {
            if (!e.getTypeParameters().isEmpty()) {
              messager.printMessage(ERROR, "AutoFactory does not support generic types", e);
            }
            return e.getQualifiedName();
          }
        }, null);
    ImmutableListMultimap<Boolean, ? extends VariableElement> parameterMap =
        Multimaps.index(constructor.getParameters(), Functions.forPredicate(
            new Predicate<VariableElement>() {
              @Override
              public boolean apply(VariableElement parameter) {
                return parameter.getAnnotation(Provided.class) != null;
              }
            }));
    ImmutableSet<Parameter> providedParameters = Parameter.forParameterList(parameterMap.get(true));
    ImmutableSet<Parameter> passedParameters = Parameter.forParameterList(parameterMap.get(false));
    return new FactoryMethodDescriptor.Builder(declaration)
        .factoryName(declaration.getFactoryName(
            elements.getPackageOf(constructor).getQualifiedName(), classElement.getSimpleName()))
        .name("create")
        .returnType(returnType.toString())
        .publicMethod(constructor.getEnclosingElement().getModifiers().contains(PUBLIC))
        .providedParameters(providedParameters)
        .passedParameters(passedParameters)
        .creationParameters(Parameter.forParameterList(constructor.getParameters()))
        .build();
  }

  private ImmutableSet<FactoryMethodDescriptor> generateDescriptorForDefaultConstructor(
      AutoFactoryDeclaration declaration, TypeElement type) {
    return ImmutableSet.of(new FactoryMethodDescriptor.Builder(declaration)
        .factoryName(declaration.getFactoryName(
            elements.getPackageOf(type).getQualifiedName(), type.getSimpleName()))
        .name("create")
        .returnType(type.getQualifiedName().toString())
        .publicMethod(type.getModifiers().contains(PUBLIC))
        .passedParameters(ImmutableSet.<Parameter>of())
        .creationParameters(ImmutableSet.<Parameter>of())
        .providedParameters(ImmutableSet.<Parameter>of())
        .build());
  }
}
