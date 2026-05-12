package com.tritech.javaparser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.comments.JavadocComment;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.*;

public class App {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JavaParser javaParser;

    static {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        javaParser = new JavaParser(config);
    }

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(8080);

        app.post("/parse", App::handleParse);
        app.get("/health", ctx -> ctx.result("ok"));

        System.out.println("[JavaParser] Servicio iniciado en puerto 8080");
    }

    private static void handleParse(Context ctx) {
        try {
            JsonNode body = mapper.readTree(ctx.body());
            String source = body.path("source").asText("");
            String filePath = body.path("file_path").asText("");

            if (source.isBlank()) {
                ctx.status(400).result("{\"error\":\"source is required\"}");
                return;
            }

            ObjectNode result = parseJava(source, filePath);
            ctx.json(result);
        } catch (Exception e) {
            ctx.status(500).result("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private static ObjectNode parseJava(String source, String filePath) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode entities = mapper.createArrayNode();
        root.set("entities", entities);

        javaParser.parse(source).getResult().ifPresent(cu -> {
            // Recorrer tipos (clases, interfaces, enums, records)
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                if (type instanceof AnnotationDeclaration) continue;

                ObjectNode entity = mapper.createObjectNode();
                
                // Determinar si es interfaz (solo ClassOrInterfaceDeclaration tiene isInterface)
                boolean isInterface = false;
                if (type instanceof ClassOrInterfaceDeclaration) {
                    isInterface = ((ClassOrInterfaceDeclaration) type).isInterface();
                }
                
                entity.put("type", isInterface ? "Interface" : "Class");
                entity.put("name", type.getNameAsString());
                entity.put("file_path", filePath);
                entity.put("start_line", type.getBegin().map(b -> b.line).orElse(0));
                entity.put("end_line", type.getEnd().map(b -> b.line).orElse(0));
                entity.put("signature", type.getModifiers().toString().replace("[", "").replace("]", "") + " " + type.toString().split("\\{")[0].trim());
                entity.put("docstring", extractJavadoc(type));
                entity.put("code", type.toString());

                ArrayNode annotations = mapper.createArrayNode();
                for (AnnotationExpr ann : type.getAnnotations()) {
                    annotations.add(ann.getNameAsString());
                }
                entity.set("annotations", annotations);

                ArrayNode relations = mapper.createArrayNode();

                // Herencia (extends) / implementación — solo para ClassOrInterfaceDeclaration
                if (type instanceof ClassOrInterfaceDeclaration cdecl) {
                    cdecl.getExtendedTypes().forEach(ext -> {
                        ObjectNode rel = createRelation("EXTENDS", ext.getNameAsString(), "Class");
                        relations.add(rel);
                    });
                    cdecl.getImplementedTypes().forEach(impl -> {
                        ObjectNode rel = createRelation("IMPLEMENTS", impl.getNameAsString(), "Interface");
                        relations.add(rel);
                    });
                }

                // Campos
                for (FieldDeclaration field : type.getFields()) {
                    for (VariableDeclarator var : field.getVariables()) {
                        String fieldName = var.getNameAsString();
                        String fieldType = var.getType().asString();

                        // Entidad campo
                        ObjectNode fieldEntity = mapper.createObjectNode();
                        fieldEntity.put("type", "Field");
                        fieldEntity.put("name", fieldName);
                        fieldEntity.put("file_path", filePath);
                        fieldEntity.put("start_line", field.getBegin().map(b -> b.line).orElse(0));
                        fieldEntity.put("end_line", field.getEnd().map(b -> b.line).orElse(0));
                        fieldEntity.put("signature", field.toString().split(";")[0].trim());
                        fieldEntity.put("code", field.toString());
                        fieldEntity.set("annotations", mapper.createArrayNode());
                        fieldEntity.set("relations", mapper.createArrayNode());
                        entities.add(fieldEntity);

                        // Relación clase -> campo
                        ObjectNode hasField = createRelation("HAS_FIELD", fieldName, "Field");
                        hasField.putObject("properties").put("field_type", fieldType);
                        relations.add(hasField);

                        // Detectar @Autowired / @Inject
                        for (AnnotationExpr ann : field.getAnnotations()) {
                            String annName = ann.getNameAsString();
                            if (annName.equals("Autowired") || annName.equals("Inject") || annName.equals("Resource")) {
                                ObjectNode injected = createRelation("INJECTED", fieldType, "Class");
                                injected.putObject("properties").put("field_name", fieldName).put("annotation", annName);
                                relations.add(injected);
                            }
                        }
                    }
                }

                // Métodos
                for (MethodDeclaration method : type.getMethods()) {
                    String methodName = method.getNameAsString();

                    ObjectNode methodEntity = mapper.createObjectNode();
                    methodEntity.put("type", "Method");
                    methodEntity.put("name", methodName);
                    methodEntity.put("file_path", filePath);
                    methodEntity.put("start_line", method.getBegin().map(b -> b.line).orElse(0));
                    methodEntity.put("end_line", method.getEnd().map(b -> b.line).orElse(0));
                    methodEntity.put("signature", method.getDeclarationAsString(false, false, false));
                    methodEntity.put("docstring", extractJavadoc(method));
                    methodEntity.put("code", method.toString());

                    ArrayNode methodAnnotations = mapper.createArrayNode();
                    for (AnnotationExpr ann : method.getAnnotations()) {
                        methodAnnotations.add(ann.getNameAsString());
                    }
                    methodEntity.set("annotations", methodAnnotations);

                    ArrayNode methodRelations = mapper.createArrayNode();
                    // Llamadas a métodos dentro del cuerpo
                    Set<String> calledMethods = new LinkedHashSet<>();
                    method.findAll(MethodCallExpr.class).forEach(call -> {
                        calledMethods.add(call.getNameAsString());
                    });
                    calledMethods.forEach(called -> {
                        methodRelations.add(createRelation("CALLS", called, "Method"));
                    });
                    methodEntity.set("relations", methodRelations);
                    entities.add(methodEntity);

                    // Relación clase -> método
                    relations.add(createRelation("HAS_METHOD", methodName, "Method"));
                }

                // Constructores
                for (ConstructorDeclaration ctor : type.getConstructors()) {
                    String ctorName = ctor.getNameAsString();
                    ObjectNode ctorEntity = mapper.createObjectNode();
                    ctorEntity.put("type", "Method");
                    ctorEntity.put("name", ctorName);
                    ctorEntity.put("file_path", filePath);
                    ctorEntity.put("start_line", ctor.getBegin().map(b -> b.line).orElse(0));
                    ctorEntity.put("end_line", ctor.getEnd().map(b -> b.line).orElse(0));
                    ctorEntity.put("signature", ctor.getDeclarationAsString(false, false, false));
                    ctorEntity.put("code", ctor.toString());
                    ctorEntity.set("annotations", mapper.createArrayNode());

                    ArrayNode ctorRelations = mapper.createArrayNode();
                    Set<String> calledMethods = new LinkedHashSet<>();
                    ctor.findAll(MethodCallExpr.class).forEach(call -> {
                        calledMethods.add(call.getNameAsString());
                    });
                    calledMethods.forEach(called -> ctorRelations.add(createRelation("CALLS", called, "Method")));
                    ctorEntity.set("relations", ctorRelations);
                    entities.add(ctorEntity);

                    relations.add(createRelation("HAS_METHOD", ctorName, "Method"));
                }

                // Imports como relaciones IMPORTS
                cu.getImports().forEach(imp -> {
                    String impName = imp.getNameAsString();
                    if (!imp.isAsterisk()) {
                        String simpleName = impName.contains(".") ? impName.substring(impName.lastIndexOf('.') + 1) : impName;
                        relations.add(createRelation("IMPORTS", simpleName, "Class"));
                    }
                });

                entity.set("relations", relations);
                entities.add(entity);
            }
        });

        return root;
    }

    private static ObjectNode createRelation(String type, String targetName, String targetType) {
        ObjectNode rel = mapper.createObjectNode();
        rel.put("type", type);
        rel.put("target_name", targetName);
        rel.put("target_type", targetType);
        rel.putObject("properties");
        return rel;
    }

    private static String extractJavadoc(BodyDeclaration<?> node) {
        Optional<JavadocComment> javadoc = node.getJavadocComment();
        if (javadoc.isPresent()) {
            return javadoc.get().getContent();
        }
        return "";
    }
}
