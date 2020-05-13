package com.alibaba.testable.processor;

import com.alibaba.testable.annotation.Testable;
import com.alibaba.testable.generator.CallSuperMethod;
import com.alibaba.testable.generator.model.Statement;
import com.alibaba.testable.translator.TestableClassTranslator;
import com.alibaba.testable.translator.TestableFieldTranslator;
import com.alibaba.testable.util.ConstPool;
import com.squareup.javapoet.*;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author flin
 */
@SupportedAnnotationTypes("com.alibaba.testable.annotation.Testable")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class TestableProcessor extends BaseProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Testable.class);
        for (Element element : elements) {
            if (element.getKind().isClass()) {
                processClassElement(element);
            } else if (element.getKind().isField()) {
                processFieldElement(element);
            }
        }
        return true;
    }

    private void processFieldElement(Element field) {
        JCTree tree = trees.getTree(field);
        tree.accept(new TestableFieldTranslator(treeMaker));
    }

    private void processClassElement(Element clazz) {
        String packageName = elementUtils.getPackageOf(clazz).getQualifiedName().toString();
        String testableTypeName = clazz.getSimpleName().toString().replace(".", "_") + "Testable";
        String fullQualityTypeName =  packageName + "." + testableTypeName;
        try {
            JavaFileObject jfo = filter.createSourceFile(fullQualityTypeName);
            Writer writer = jfo.openWriter();
            writer.write(createTestableClass(clazz, packageName, testableTypeName));
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String createTestableClass(Element clazz, String packageName, String className) {
        JCTree tree = trees.getTree(clazz);
        TestableClassTranslator translator = new TestableClassTranslator();
        tree.accept(translator);

        List<MethodSpec> methodSpecs = new ArrayList<>();
        for (JCTree.JCMethodDecl method : translator.getMethods()) {
            if (isNoncallableMethod(method)) {
                continue;
            }
            if (isConstructorMethod(method)) {
                buildConstructorMethod(clazz, methodSpecs, method);
            } else {
                buildMemberMethod(clazz, methodSpecs, method);
            }
        }

        TypeSpec.Builder builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(clazz.asType());
        for (MethodSpec m : methodSpecs) {
            builder.addMethod(m);
        }
        TypeSpec testableClass = builder.build();
        JavaFile javaFile = JavaFile.builder(packageName, testableClass).build();
        return javaFile.toString();
    }

    private void buildMemberMethod(Element classElement, List<MethodSpec> methodSpecs, JCTree.JCMethodDecl method) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(method.name.toString())
            .addModifiers(toPublicFlags(method.getModifiers()))
            .returns(TypeName.get(((Type.MethodType)method.sym.type).restype));
        for (JCTree.JCVariableDecl p : method.getParameters()) {
            builder.addParameter(getParameterSpec(p));
        }
        if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
            builder.addException(Exception.class);
        } else {
            builder.addAnnotation(Override.class);
            for (JCTree.JCExpression exception : method.getThrows()) {
                builder.addException(TypeName.get(exception.type));
            }
        }
        addStatements(builder, classElement, method);
        methodSpecs.add(builder.build());
    }

    private void buildConstructorMethod(Element classElement, List<MethodSpec> methodSpecs,
                                        JCTree.JCMethodDecl method) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC);
        for (JCTree.JCVariableDecl p : method.getParameters()) {
            builder.addParameter(getParameterSpec(p));
        }
        addStatements(builder, classElement, method);
        methodSpecs.add(builder.build());
    }

    private void addStatements(MethodSpec.Builder builder, Element classElement, JCTree.JCMethodDecl method) {
        Statement[] statements = new CallSuperMethod(classElement.getSimpleName().toString(), method).invoke();
        for (Statement s : statements) {
            builder.addStatement(s.getLine(), s.getParams());
        }
    }

    private boolean isConstructorMethod(JCTree.JCMethodDecl method) {
        return method.name.toString().equals(ConstPool.CONSTRUCTOR_NAME);
    }

    private boolean isNoncallableMethod(JCTree.JCMethodDecl method) {
        return method.getModifiers().getFlags().contains(Modifier.ABSTRACT);
    }

    private Set<Modifier> toPublicFlags(JCTree.JCModifiers modifiers) {
        Set<Modifier> flags = new HashSet<>(modifiers.getFlags());
        flags.remove(Modifier.PRIVATE);
        flags.remove(Modifier.PROTECTED);
        flags.add(Modifier.PUBLIC);
        return flags;
    }

    private ParameterSpec getParameterSpec(JCTree.JCVariableDecl type) {
        return ParameterSpec.builder(TypeName.get(type.sym.type), type.name.toString()).build();
    }

}
