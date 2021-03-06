/**
 * Copyright 2019 Jordan Zimmerman
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
package io.soabase.recordbuilder.processor;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderMetaData;
import io.soabase.recordbuilder.core.RecordInterface;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Generated;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class RecordBuilderProcessor extends AbstractProcessor {
    private static final String RECORD_BUILDER = RecordBuilder.class.getName();
    private static final String RECORD_BUILDER_INCLUDE = RecordBuilder.Include.class.getName().replace('$', '.');
    private static final String RECORD_INTERFACE = RecordInterface.class.getName();
    private static final String RECORD_INTERFACE_INCLUDE = RecordInterface.Include.class.getName().replace('$', '.');

    static final AnnotationSpec generatedRecordBuilderAnnotation = AnnotationSpec.builder(Generated.class).addMember("value", "$S", RecordBuilder.class.getName()).build();
    static final AnnotationSpec generatedRecordInterfaceAnnotation = AnnotationSpec.builder(Generated.class).addMember("value", "$S", RecordInterface.class.getName()).build();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        annotations.forEach(annotation -> roundEnv.getElementsAnnotatedWith(annotation).forEach(element -> process(annotation, element)));
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(RECORD_BUILDER, RECORD_BUILDER_INCLUDE, RECORD_INTERFACE, RECORD_INTERFACE_INCLUDE);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        // we don't directly return RELEASE_14 as that may 
        // not exist in prior releases
        // if we're running on an older release, returning latest()
        // is fine as we won't encounter any records anyway
        return SourceVersion.latest();
    }

    private void process(TypeElement annotation, Element element) {
        var metaData = new RecordBuilderMetaDataLoader(processingEnv, s -> processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, s)).getMetaData();

        String annotationClass = annotation.getQualifiedName().toString();
        if ( annotationClass.equals(RECORD_BUILDER) )
        {
            processRecordBuilder((TypeElement)element, metaData, Optional.empty());
        }
        else if ( annotationClass.equals(RECORD_INTERFACE) )
        {
            processRecordInterface((TypeElement)element, element.getAnnotation(RecordInterface.class).addRecordBuilder(), metaData, Optional.empty());
        }
        else if ( annotationClass.equals(RECORD_BUILDER_INCLUDE) || annotationClass.equals(RECORD_INTERFACE_INCLUDE) )
        {
            processIncludes(element, metaData, annotationClass);
        }
        else
        {
            throw new RuntimeException("Unknown annotation: " + annotation);
        }
    }

    private void processIncludes(Element element, RecordBuilderMetaData metaData, String annotationClass) {
        var annotationMirrorOpt = ElementUtils.findAnnotationMirror(processingEnv, element, annotationClass);
        if ( annotationMirrorOpt.isEmpty() )
        {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Could not get annotation mirror for: " + annotationClass, element);
        }
        else
        {
            var values = processingEnv.getElementUtils().getElementValuesWithDefaults(annotationMirrorOpt.get());
            var classes = ElementUtils.getAnnotationValue(values, "value");
            if ( classes.isEmpty() )
            {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Could not get annotation value for: " + annotationClass, element);
            }
            else
            {
                var packagePattern = ElementUtils.getStringAttribute(ElementUtils.getAnnotationValue(values, "packagePattern").orElse(null), "*");
                var classesMirrors = ElementUtils.getClassesAttribute(classes.get());
                for ( TypeMirror mirror : classesMirrors )
                {
                    TypeElement typeElement = (TypeElement)processingEnv.getTypeUtils().asElement(mirror);
                    if ( typeElement == null )
                    {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Could not get element for: " + mirror, element);
                    }
                    else
                    {
                        var packageName = buildPackageName(packagePattern, element, typeElement);
                        if (packageName != null)
                        {
                            if ( annotationClass.equals(RECORD_INTERFACE_INCLUDE) )
                            {
                                var addRecordBuilderOpt = ElementUtils.getAnnotationValue(values, "addRecordBuilder");
                                var addRecordBuilder = addRecordBuilderOpt.map(ElementUtils::getBooleanAttribute).orElse(true);
                                processRecordInterface(typeElement, addRecordBuilder, metaData, Optional.of(packageName));
                            }
                            else
                            {
                                processRecordBuilder(typeElement, metaData, Optional.of(packageName));
                            }
                        }
                    }
                }
            }
        }
    }

    private String buildPackageName(String packagePattern, Element builderElement, TypeElement includedClass) {
        PackageElement includedClassPackage = findPackageElement(includedClass, includedClass);
        if (includedClassPackage == null) {
            return null;
        }
        String replaced = packagePattern.replace("*", includedClassPackage.getQualifiedName().toString());
        if (builderElement instanceof PackageElement) {
            return replaced.replace("@", ((PackageElement)builderElement).getQualifiedName().toString());
        }
        return replaced.replace("@", ((PackageElement)builderElement.getEnclosingElement()).getQualifiedName().toString());
    }

    private PackageElement findPackageElement(Element actualElement, Element includedClass) {
        if (includedClass == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Element has not package", actualElement);
            return null;
        }
        if (includedClass.getEnclosingElement() instanceof PackageElement) {
            return (PackageElement)includedClass.getEnclosingElement();
        }
        return findPackageElement(actualElement, includedClass.getEnclosingElement());
    }

    private void processRecordInterface(TypeElement element, boolean addRecordBuilder, RecordBuilderMetaData metaData, Optional<String> packageName) {
        if ( !element.getKind().isInterface() )
        {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "RecordInterface only valid for interfaces.", element);
            return;
        }
        var internalProcessor = new InternalRecordInterfaceProcessor(processingEnv, element, addRecordBuilder, metaData, packageName);
        if ( !internalProcessor.isValid() )
        {
            return;
        }
        writeRecordInterfaceJavaFile(element, internalProcessor.packageName(), internalProcessor.recordClassType(), internalProcessor.recordType(), metaData, internalProcessor::toRecord);
    }

    private void processRecordBuilder(TypeElement record, RecordBuilderMetaData metaData, Optional<String> packageName) {
        // we use string based name comparison for the element kind,
        // as the ElementKind.RECORD enum doesn't exist on JRE releases
        // older than Java 14, and we don't want to throw unexpected
        // NoSuchFieldErrors
        if ( !"RECORD".equals(record.getKind().name()) )
        {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "RecordBuilder only valid for records.", record);
            return;
        }
        var internalProcessor = new InternalRecordBuilderProcessor(record, metaData, packageName);
        writeRecordBuilderJavaFile(record, internalProcessor.packageName(), internalProcessor.builderClassType(), internalProcessor.builderType(), metaData);
    }

    private void writeRecordBuilderJavaFile(TypeElement record, String packageName, ClassType builderClassType, TypeSpec builderType, RecordBuilderMetaData metaData) {
        // produces the Java file
        JavaFile javaFile = javaFileBuilder(packageName, builderType, metaData);
        Filer filer = processingEnv.getFiler();
        try
        {
            String fullyQualifiedName = packageName.isEmpty() ? builderClassType.name() : (packageName + "." + builderClassType.name());
            JavaFileObject sourceFile = filer.createSourceFile(fullyQualifiedName);
            try (Writer writer = sourceFile.openWriter())
            {
                javaFile.writeTo(writer);
            }
        }
        catch ( IOException e )
        {
            handleWriteError(record, e);
        }
    }

    private void writeRecordInterfaceJavaFile(TypeElement element, String packageName, ClassType classType, TypeSpec type, RecordBuilderMetaData metaData, Function<String, String> toRecordProc) {
        JavaFile javaFile = javaFileBuilder(packageName, type, metaData);

        String classSourceCode = javaFile.toString();
        int generatedIndex = classSourceCode.indexOf("@Generated");
        String recordSourceCode = toRecordProc.apply(classSourceCode);

        Filer filer = processingEnv.getFiler();
        try
        {
            String fullyQualifiedName = packageName.isEmpty() ? classType.name() : (packageName + "." + classType.name());
            JavaFileObject sourceFile = filer.createSourceFile(fullyQualifiedName);
            try (Writer writer = sourceFile.openWriter())
            {
                writer.write(recordSourceCode);
            }
        }
        catch ( IOException e )
        {
            handleWriteError(element, e);
        }
    }

    private JavaFile javaFileBuilder(String packageName, TypeSpec type, RecordBuilderMetaData metaData) {
        var javaFileBuilder = JavaFile.builder(packageName, type).skipJavaLangImports(true).indent(metaData.fileIndent());
        var comment = metaData.fileComment();
        if ( (comment != null) && !comment.isEmpty() )
        {
            javaFileBuilder.addFileComment(comment);
        }
        return javaFileBuilder.build();
    }

    private void handleWriteError(TypeElement element, IOException e) {
        String message = "Could not create source file";
        if ( e.getMessage() != null )
        {
            message = message + ": " + e.getMessage();
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
