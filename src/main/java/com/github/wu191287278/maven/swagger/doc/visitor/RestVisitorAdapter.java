package com.github.wu191287278.maven.swagger.doc.visitor;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.description.JavadocDescription;
import com.github.javaparser.javadoc.description.JavadocDescriptionElement;
import com.github.wu191287278.maven.swagger.doc.domain.Request;
import com.github.wu191287278.maven.swagger.doc.utils.CamelUtils;
import com.google.common.collect.ImmutableMap;
import io.swagger.models.*;
import io.swagger.models.parameters.*;
import io.swagger.models.properties.ObjectProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author yu.wu
 */
public class RestVisitorAdapter extends VoidVisitorAdapter<Swagger> {

    private Logger log = LoggerFactory.getLogger(RestVisitorAdapter.class);

    private final ResolveSwaggerType resolveSwaggerType = new ResolveSwaggerType();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean camel = true;

    private final Set<String> controllers = new HashSet<>(Arrays.asList("Controller", "RestController", "FeignClient"));

    private final Set<String> mappings = new HashSet<>(Arrays.asList("RequestMapping",
            "GetMapping", "PutMapping", "DeleteMapping", "PostMapping", "FeignClient", "PatchMapping"));

    private final Map<String, String> methods = ImmutableMap.of("GetMapping", "get",
            "PostMapping", "post",
            "DeleteMapping", "delete",
            "PutMapping", "put",
            "PatchMapping", "patch"
    );

    private final Map<String, String> headers = new HashMap<>();

    {
        try {
            Class<?> mediaTypeClazz = Class.forName("org.springframework.http.MediaType");
            Field[] fields = mediaTypeClazz.getFields();
            for (Field field : fields) {
                try {
                    Object o = field.get(null);
                    headers.put(field.getName(), o.toString());
                } catch (IllegalAccessException e) {
                    log.warn(e.getMessage());
                }
            }
        } catch (Exception e) {
            headers.put("APPLICATION_FORM_URLENCODED", "application/x-www-form-urlencoded");
            headers.put("APPLICATION_JSON_VALUE", "application/json");
            headers.put("APPLICATION_JSON_UTF8_VALUE", "application/json;charset=UTF-8");
            headers.put("APPLICATION_OCTET_STREAM_VALUE", "application/octet-stream");
            headers.put("APPLICATION_XML_VALUE", "application/xml");
            headers.put("TEXT_HTML_VALUE", "text/html");
        }

    }

    private Consumer<String> consumer = s -> {

    };

    public RestVisitorAdapter() {
    }


    public RestVisitorAdapter(Consumer<String> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void visit(MethodDeclaration n, Swagger swagger) {
        List<AnnotationExpr> annotationExprs = n.getAnnotations()
                .stream()
                .filter(a -> mappings.contains(a.getNameAsString()))
                .collect(Collectors.toList());

        if (annotationExprs.isEmpty()) return;


        Request request = new Request();
        Map<String, Path> paths = swagger.getPaths();
        parse((ClassOrInterfaceDeclaration) n.getParentNode().get(), n, request);


        String parentMappingPath = request.getParentPath() == null ? "" : request.getParentPath();
        Operation operation = new Operation()
                .tag(request.getClazzSimpleName())
                .consumes(request.getConsumes().isEmpty() ? null : request.getConsumes())
                .produces(request.getProduces().isEmpty() ? null : request.getProduces())
                .description(request.getMethodNotes())
                .summary(request.getSummary())
                .response(200, new Response()
                        .description(request.getReturnDescription() == null ? "" : request.getReturnDescription())
                        .schema(request.getReturnType())
                )
                .deprecated(request.isDeprecated());
        operation.setParameters(request.getParameters());

        if (request.getMethodErrorDescription() != null) {
            operation.response(500, new Response().description("{\"message\":\"" + request.getMethodErrorDescription() + "\"}"));
        }

        for (Map.Entry<Integer, Response> entry : request.getResponseStatus().entrySet()) {
            operation.response(entry.getKey(), entry.getValue());
        }
        //方法上如果只打入注解没有url,将使用类上的url
        List<String> pathList = request.getPaths();
        if (pathList.isEmpty()) {
            String fullPath = ("/" + parentMappingPath).replaceAll("[/]+", "/");
            Path path = paths.computeIfAbsent(fullPath, s -> new Path());
            paths.put(fullPath, path);
            for (String method : request.getMethods()) {
                path.set(method, operation.operationId(n.getNameAsString()));
            }
        }

        for (String methodPath : pathList) {
            String fullPath = ("/" + parentMappingPath + "/" + methodPath).replaceAll("[/]+", "/");
            Path path = paths.computeIfAbsent(fullPath, s -> new Path());
            paths.put(fullPath, path);
            for (String method : request.getMethods()) {
                path.set(method, operation.operationId(n.getNameAsString()));
            }
        }

        super.visit(n, swagger);
    }


    @Override
    public void visit(ClassOrInterfaceDeclaration n, Swagger swagger) {
        List<AnnotationExpr> annotationExprs = n.getAnnotations()
                .stream()
                .filter(a -> controllers.contains(a.getNameAsString()))
                .collect(Collectors.toList());

        if (annotationExprs.isEmpty()) return;
        consumer.accept(n.getNameAsString());
        Tag tag = new Tag()
                .name(n.getNameAsString());
        swagger.addTag(tag);
        n.getJavadoc().ifPresent(c -> tag.description(StringUtils.isBlank(c.getDescription().toText()) ? null : c.getDescription().toText()));
        super.visit(n, swagger);

    }

    private void parse(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, MethodDeclaration n, Request request) {
        request.setClazzSimpleName(classOrInterfaceDeclaration.getNameAsString());
        parseMapping(classOrInterfaceDeclaration, n, request);
        parseMethodParameters(n, request);
        parseReturnType(n, request);
    }


    private void parseMapping(ClassOrInterfaceDeclaration classOrInterfaceDeclaration, MethodDeclaration n, Request request) {
        for (AnnotationExpr annotation : classOrInterfaceDeclaration.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            if (mappings.contains(annotationName)) {
                if ("FeignClient".equalsIgnoreCase(annotationName)) {
                    if (annotation instanceof NormalAnnotationExpr) {
                        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                            String name = pair.getNameAsString();
                            if ("path".equals(name)) {
                                List<String> values = parseAttribute(pair);
                                request.setParentPath(values.isEmpty() ? null : values.get(0));
                            }
                        }
                    }
                } else {
                    if (annotation instanceof SingleMemberAnnotationExpr) {
                        SingleMemberAnnotationExpr singleMemberAnnotationExpr = (SingleMemberAnnotationExpr) annotation;
                        request.setParentPath(singleMemberAnnotationExpr.getMemberValue().asStringLiteralExpr().asString());
                    }
                    if (annotation instanceof NormalAnnotationExpr) {
                        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                            String name = pair.getNameAsString();
                            if ("path".equals(name) || "value".equals(name)) {
                                List<String> values = parseAttribute(pair);
                                request.setParentPath(values.isEmpty() ? null : values.get(0));
                            }
                        }
                    }
                }
            }

            if ("Deprecated".equals(annotationName)) {
                request.setDeprecated(true);
            }
        }

        for (AnnotationExpr annotation : n.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            if (mappings.contains(annotationName)) {

                if (annotation instanceof SingleMemberAnnotationExpr) {
                    SingleMemberAnnotationExpr singleMemberAnnotationExpr = (SingleMemberAnnotationExpr) annotation;
                    Expression memberValue = singleMemberAnnotationExpr.getMemberValue();
                    if (memberValue.isStringLiteralExpr()) {
                        request.getPaths().add(memberValue.asStringLiteralExpr().asString());
                    }

                    if (memberValue.isArrayInitializerExpr()) {
                        NodeList<Expression> values = memberValue.asArrayInitializerExpr().getValues();
                        for (Expression value : values) {
                            if (value.isStringLiteralExpr()) {
                                request.getPaths().add(value.asStringLiteralExpr().asString());
                            }
                        }
                    }

                }
                if (annotation instanceof NormalAnnotationExpr) {
                    for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                        String name = pair.getNameAsString();
                        List<String> values = parseAttribute(pair);
                        if ("path".equals(name) || "value".equals(name)) {
                            request.getPaths().addAll(values);
                        }

                        if ("headers".equals(name)) {
                            request.getHeaders().addAll(values);
                        }

                        if ("produces".equals(name)) {
                            request.getProduces().addAll(values);
                        }

                        if ("consumes".equals(name)) {
                            request.getConsumes().addAll(values);
                        }

                        if ("method".equals(name)) {
                            for (String value : values) {
                                request.getMethods().add(value.toLowerCase());
                            }
                        }
                    }
                }

                if (annotationName.equals("RequestMapping") && request.getMethods().isEmpty()) {
                    request.getMethods().add("post");
                } else {
                    String method = methods.get(annotationName);
                    if (method != null) {
                        request.getMethods().add(method);
                    }
                }
            }
        }


    }

    private List<String> parseAttribute(MemberValuePair pair) {
        List<String> values = new ArrayList<>();
        Expression value = pair.getValue();
        if (value instanceof ArrayInitializerExpr) {
            ArrayInitializerExpr arrayAccessExpr = value.asArrayInitializerExpr();
            for (Expression expression : arrayAccessExpr.getValues()) {
                if (expression instanceof StringLiteralExpr) {
                    String path = expression.asStringLiteralExpr().asString();
                    values.add(path);
                }

                if (expression instanceof FieldAccessExpr) {
                    FieldAccessExpr field = expression.asFieldAccessExpr();
                    String name = field.getName().asString();
                    values.add(name);
                }
            }
        } else if (value instanceof FieldAccessExpr) {
            FieldAccessExpr field = value.asFieldAccessExpr();
            String name = field.getName().asString();
            String header = this.headers.get(name);
            if (header != null) {
                values.add(header);
            } else {
                values.add(name);
            }

        } else if (value instanceof NameExpr) {
            values.add(value.asNameExpr().getNameAsString());
        } else {
            values.add(value.asStringLiteralExpr().asString());
        }
        return values;
    }

    private void parseMethodParameters(MethodDeclaration n, Request request) {
        parseMethodComment(n, request);
        for (Parameter parameter : n.getParameters()) {
            String typeName = parameter.getType().toString();
            if (typeName.contains("HttpServletRequest")
                    || typeName.contains("HttpServletResponse")
                    || typeName.contains("HttpSession")) {
                continue;
            }
            String variableName = parameter.getNameAsString();
            Map<String, String> paramsDescription = request.getParamsDescription();
            String description = paramsDescription.get(variableName);
            Property property = resolveSwaggerType.resolve(parameter.getType());
            Property paramProperty = property instanceof ObjectProperty ? new StringProperty()
                    .description(property.getDescription()) : property;
            io.swagger.models.parameters.Parameter param = new QueryParameter()
                    .property(paramProperty);

            if (parameter.getAnnotations().isEmpty() && property instanceof ObjectProperty) {
                try {
                    ObjectProperty objectProperty = (ObjectProperty) property;
                    if (objectProperty.getProperties() != null && objectProperty.getProperties().size() > 0) {
                        for (Map.Entry<String, Property> entry : objectProperty.getProperties().entrySet()) {
                            Property value = entry.getValue();
                            QueryParameter queryParameter = new QueryParameter()
                                    .name(entry.getKey())
                                    .description(value.getDescription())
                                    .example(value.getExample() == null ? null : value.getExample().toString())
                                    .required(value.getRequired())
                                    .format(value.getFormat())
                                    .type(value.getType());
                            request.getParameters().add(queryParameter);
                        }
                        continue;
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }

            for (AnnotationExpr annotation : parameter.getAnnotations()) {
                String annotationName = annotation.getNameAsString();

                if (annotation.isAnnotationExpr()) {
                    switch (annotationName) {
                        case "PathVariable":
                            param = new PathParameter()
                                    .property(paramProperty);
                            break;
                        case "RequestBody":
                            Model model = resolveSwaggerType.convertToModel(property);
                            BodyParameter bodyParameter = new BodyParameter().schema(new ModelImpl().type("object"));
                            if (model != null) {
                                bodyParameter.schema(model);
                            }
                            param = bodyParameter;
                            break;

                        case "RequestPart":
                            param = new FormParameter()
                                    .property(paramProperty);
                            request.getConsumes().add("multipart/form-data");
                            break;
                        case "RequestHeader":
                            param = new HeaderParameter()
                                    .property(paramProperty);
                            break;
                        case "CookieValue":
                            param = new CookieParameter()
                                    .property(property);
                            break;
                        case "SpringQueryMap":
                            try {
                                if (property instanceof ObjectProperty) {
                                    ObjectProperty objectProperty = (ObjectProperty) property;
                                    if (objectProperty.getProperties() != null && objectProperty.getProperties().size() > 0) {
                                        for (Map.Entry<String, Property> entry : objectProperty.getProperties().entrySet()) {
                                            Property value = entry.getValue();
                                            QueryParameter queryParameter = new QueryParameter()
                                                    .name(entry.getKey())
                                                    .description(value.getDescription())
                                                    .example(value.getExample() == null ? null : value.getExample().toString())
                                                    .required(value.getRequired())
                                                    .format(value.getFormat())
                                                    .type(value.getType());
                                            request.getParameters().add(queryParameter);
                                        }
                                    }
                                    break;
                                }
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                    }

                    if (param instanceof PathParameter || param instanceof QueryParameter || param instanceof HeaderParameter || param instanceof CookieParameter) {
                        if (annotation.isSingleMemberAnnotationExpr()) {
                            param.setRequired(true);
                            SingleMemberAnnotationExpr single = annotation.asSingleMemberAnnotationExpr();
                            Expression expression = single.getMemberValue();
                            if (expression.isStringLiteralExpr()) {
                                String value = expression.asStringLiteralExpr().asString();
                                if (StringUtils.isNotBlank(value)) {
                                    variableName = value;
                                }
                            }
                        }

                        if (annotation.isNormalAnnotationExpr()) {
                            boolean isRequire = true;
                            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                                if ("required".equals(pair.getNameAsString())) {
                                    isRequire = pair.getValue().asBooleanLiteralExpr().getValue();
                                }

                                if ("defaultValue".equals(pair.getNameAsString())) {
                                    Expression value = pair.getValue();
                                    if (value.isStringLiteralExpr()) {
                                        ((AbstractSerializableParameter) param).setDefault(value.asStringLiteralExpr().asString());
                                    }
                                    isRequire = false;
                                }

                                if ("value".equals(pair.getNameAsString()) || "name".equals(pair.getNameAsString())) {
                                    Expression expression = pair.getValue();
                                    if (expression.isStringLiteralExpr()) {
                                        String value = expression.asStringLiteralExpr().asString();
                                        if (StringUtils.isNotBlank(value)) {
                                            variableName = value;
                                        }
                                    }
                                    if (expression.isFieldAccessExpr() && "RequestHeader".equalsIgnoreCase(annotation.getNameAsString())) {
                                        FieldAccessExpr fieldAccessExpr = expression.asFieldAccessExpr();
                                        SimpleName name = fieldAccessExpr.getName();
                                        variableName = name.asString().toLowerCase().replace("_", "-");
                                    }
                                }
                            }
                            param.setRequired(isRequire);
                        }
                    }
                }
            }


            param.setDescription(property.getDescription() != null ? property.getDescription() : description);
            param.setName(variableName);

            request.getParameters().add(param);
        }
    }

    private void parseMethodComment(MethodDeclaration n, Request request) {
        n.getJavadocComment().ifPresent(c -> {
            if (c.isJavadocComment()) {
                JavadocComment javadocComment = c.asJavadocComment();
                Javadoc parse = javadocComment.parse();
                JavadocDescription description = parse.getDescription();
                request.setSummary(description.toText());
                for (JavadocBlockTag blockTag : parse.getBlockTags()) {
                    switch (blockTag.getTagName().toLowerCase()) {
                        case "throws":
                            request.setMethodErrorDescription(blockTag.getContent().toText());
                            break;
                        case "return":
                            request.setReturnDescription(blockTag.getContent().toText());
                            break;
                        case "apinote":
                            request.setMethodNotes(blockTag.getContent().toText());
                            break;
                        case "responsestatus":
                            try {
                                String text = blockTag.getContent().toText();
                                String[] split = text.trim().split("\\s+|\\t");
                                if (split.length > 1 && NumberUtils.isDigits(split[0])) {
                                    String reason = String.join("", Arrays.copyOfRange(split, 1, split.length));
                                    Response response = new Response();
                                    if (reason.startsWith("{")) {
                                        response.description(reason);
                                    } else {
                                        response.description("{\"message\":\"" + reason + "\"}");
                                    }

                                    request.getResponseStatus().put(Integer.parseInt(split[0]), response);
                                }
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                            break;
                        default:
                            blockTag.getName().ifPresent(t -> request.getParamsDescription().put(t, blockTag.getContent().toText()));
                            break;
                    }
                }
            }
        });
    }

    private void parseReturnType(MethodDeclaration n, Request request) {
        Type type = n.getType();
        if (type.isVoidType()) {
            return;
        }

        String name = type.toString();

        if ((name.contains("ResponseEntity") || name.contains("Mono")) && type.isClassOrInterfaceType()) {
            Optional<NodeList<Type>> types = type.asClassOrInterfaceType().getTypeArguments();
            if (types.isPresent()) {
                type = types.get().isEmpty() ? type : types.get().get(0);
            }
        }

        Property property = resolveSwaggerType.resolve(type);
        if (property.getName() != null) {
            request.setReturnType(new RefProperty("#/definitions/" + property.getName()));
        } else {
            request.setReturnType(property);
        }
    }

    public Map<String, Model> getModelMap() {
        Map<String, Model> modelMap = resolveSwaggerType.getModelMap();
        if (!this.camel) {
            for (Map.Entry<String, Model> entry : modelMap.entrySet()) {
                Model model = entry.getValue();
                if (model instanceof ModelImpl) {
                    ModelImpl modelImpl = (ModelImpl) model;
                    Map<String, Property> properties = modelImpl.getProperties();
                    Map<String, Property> convertModelMap = new HashMap<>();
                    if (properties != null) {
                        for (Map.Entry<String, Property> modelEntry : properties.entrySet()) {
                            convertModelMap.put(CamelUtils.toSnake(modelEntry.getKey()), modelEntry.getValue());
                        }
                        properties.clear();
                        modelImpl.setProperties(convertModelMap);
                    }
                }
            }
        }
        return modelMap;
    }

    public RestVisitorAdapter setCamel(boolean camel) {
        this.camel = camel;
        return this;
    }

}
