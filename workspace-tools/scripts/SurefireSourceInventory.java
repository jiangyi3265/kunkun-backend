import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import javax.tools.*;
import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Parse only: never compile, load, or execute application/JUnit test classes. */
public class SurefireSourceInventory {
    static String json(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toCharArray())switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}
        return out.append('"').toString();
    }
    static boolean selected(String name) {return name.startsWith("Test")||name.endsWith("Test")||name.endsWith("Tests")||name.endsWith("TestCase");}
    static String annotationName(AnnotationTree annotation,Map<String,String> imports,List<String> stars) {
        String name=annotation.getAnnotationType().toString();if(name.contains("."))return name;
        if(imports.containsKey(name))return imports.get(name);
        // Unknown wildcard names may be composed annotations in the same package; never guess.
        String owner=switch(name){case "Transactional"->"org.springframework.transaction.annotation";case "ParameterizedTest"->"org.junit.jupiter.params";case "ValueSource","NullSource","EmptySource","CsvSource","MethodSource","ArgumentsSource","EnumSource"->"org.junit.jupiter.params.provider";case "Test","BeforeEach","AfterEach","BeforeAll","AfterAll","Disabled","DisplayName","Tag","Tags","Timeout","Nested","RepeatedTest","TestFactory","TestTemplate"->"org.junit.jupiter.api";default->"";};
        if(stars.contains(owner))return owner+"."+name;
        return name;
    }
    static List<String> problems=new ArrayList<>();
    record Definition(ClassTree type, CompilationUnitTree unit) {}
    record Member(Tree tree, CompilationUnitTree unit) {}
    static Map<String,Definition> definitions=new HashMap<>();
    static String packageName(CompilationUnitTree unit){return unit.getPackageName()==null?"":unit.getPackageName().toString();}
    static Map<String,String> imports(CompilationUnitTree unit){Map<String,String> result=new HashMap<>();for(ImportTree i:unit.getImports())if(!i.isStatic()){String n=i.getQualifiedIdentifier().toString();if(!n.endsWith(".*"))result.put(n.substring(n.lastIndexOf('.')+1),n);}return result;}
    static List<String> stars(CompilationUnitTree unit){List<String> result=new ArrayList<>();for(ImportTree i:unit.getImports())if(!i.isStatic()){String n=i.getQualifiedIdentifier().toString();if(n.endsWith(".*"))result.add(n.substring(0,n.length()-2));}return result;}
    static List<Member> members(Definition definition,Set<String> visiting){
        ClassTree type=definition.type();CompilationUnitTree unit=definition.unit();String name=packageName(unit)+"."+type.getSimpleName();
        if(!visiting.add(name)){problems.add(name+": cyclic test inheritance");return List.of();}
        Map<String,Member> result=new LinkedHashMap<>();
        if(!type.getImplementsClause().isEmpty())problems.add(name+": interface default tests require an explicit completeness policy");
        if(type.getExtendsClause()!=null){String parent=type.getExtendsClause().toString();if(!parent.contains("."))parent=imports(unit).getOrDefault(parent,packageName(unit)+"."+parent);
            Definition base=definitions.get(parent);if(base==null)problems.add(name+": unresolved superclass "+parent);else for(Member member:members(base,new HashSet<>(visiting))){
                if(member.tree() instanceof MethodTree method){Set<Modifier> flags=method.getModifiers().getFlags();if(flags.contains(Modifier.PRIVATE)||flags.contains(Modifier.STATIC))continue;
                    if(!packageName(unit).equals(packageName(member.unit()))&&!flags.contains(Modifier.PUBLIC)&&!flags.contains(Modifier.PROTECTED)){problems.add(name+": cross-package package-private inheritance unsupported");continue;}}
                result.put(signature(member.tree()),member);
            }
        }
        for(Tree member:type.getMembers())result.put(signature(member),new Member(member,unit));
        return new ArrayList<>(result.values());
    }
    static String signature(Tree member){if(member instanceof MethodTree method)return method.getName()+"("+String.join(",",method.getParameters().stream().map(v->v.getType().toString()).toList())+")";return member.toString();}
    public static void main(String[] args)throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("A JDK with javac Trees is required");
        List<String> paths=Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8);if(paths.isEmpty())throw new IllegalStateException("No Java test source files");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8);
        JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--release","17"),null,manager.getJavaFileObjectsFromStrings(paths));
        List<String> suites=new ArrayList<>();
        List<CompilationUnitTree> units=new ArrayList<>();task.parse().forEach(units::add);
        for(CompilationUnitTree unit:units)for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree type)definitions.put(packageName(unit)+"."+type.getSimpleName(),new Definition(type,unit));
        for(CompilationUnitTree unit:units) {
            String packageName=unit.getPackageName()==null?"":unit.getPackageName().toString();
            for(Tree declaration:unit.getTypeDecls())if(declaration instanceof ClassTree type && selected(type.getSimpleName().toString()) && !type.getModifiers().getFlags().contains(Modifier.ABSTRACT)) {
                String name=(packageName.isEmpty()?"":packageName+".")+type.getSimpleName();
                List<String> methods=new ArrayList<>();Set<String> methodNames=new HashSet<>();
                for(Member entry:members(new Definition(type,unit),new HashSet<>())) {
                    Tree member=entry.tree();Map<String,String> imports=imports(entry.unit());List<String> stars=stars(entry.unit());
                    if(member instanceof ClassTree nested)for(AnnotationTree a:nested.getModifiers().getAnnotations())if(annotationName(a,imports,stars).endsWith(".Nested"))problems.add(name+": @Nested needs an explicit report mapping");
                    if(!(member instanceof MethodTree method))continue;
                    String kind=null;AnnotationTree values=null;boolean unsupported=false;
                    for(AnnotationTree annotation:method.getModifiers().getAnnotations()) {
                        String a=annotationName(annotation,imports,stars);
                        if(a.equals("org.junit.jupiter.api.Test")||a.equals("org.junit.Test")){if(kind!=null)unsupported=true;kind="test";}
                        else if(a.equals("org.junit.jupiter.params.ParameterizedTest")){if(kind!=null)unsupported=true;kind="parameterized";}
                        else if(a.equals("org.junit.jupiter.params.provider.ValueSource")){if(values!=null)unsupported=true;values=annotation;}
                        else if(a.startsWith("org.junit.jupiter.params.provider.")||a.endsWith(".RepeatedTest")||a.endsWith(".TestFactory")||a.endsWith(".TestTemplate")){unsupported=true;}
                        else if(!Set.of("org.junit.jupiter.api.BeforeEach","org.junit.jupiter.api.AfterEach","org.junit.jupiter.api.BeforeAll","org.junit.jupiter.api.AfterAll","org.junit.jupiter.api.Disabled","org.junit.jupiter.api.DisplayName","org.junit.jupiter.api.Tag","org.junit.jupiter.api.Tags","org.junit.jupiter.api.Timeout","Override","SuppressWarnings","Deprecated","java.lang.Override","java.lang.SuppressWarnings","java.lang.Deprecated","org.springframework.transaction.annotation.Transactional").contains(a)&&!a.startsWith("org.junit.jupiter.api.condition.")) {
                            problems.add(name+"#"+method.getName()+": unsupported method annotation "+a+" (possible composed test)");
                        }
                    }
                    if(unsupported){problems.add(name+"#"+method.getName()+": dynamic/combined test sources need an explicit completeness policy");continue;}
                    if(kind==null)continue;
                    String methodName=method.getName().toString();if(!methodNames.add(methodName))problems.add(name+": overloaded test names need an explicit report mapping");
                    int count=1;
                    if(kind.equals("parameterized")) {
                        count=0;
                        if(values!=null && values.getArguments().size()==1) {
                            ExpressionTree argument=values.getArguments().get(0);if(argument instanceof AssignmentTree assignment)argument=assignment.getExpression();
                            List<? extends ExpressionTree> literals=argument instanceof NewArrayTree array?array.getInitializers():List.of(argument);
                            if(literals!=null && !literals.isEmpty() && literals.stream().allMatch(v->v instanceof LiteralTree||v instanceof UnaryTree u && u.getExpression() instanceof LiteralTree))count=literals.size();
                        }
                        if(count==0)problems.add(name+"#"+methodName+": only a single literal @ValueSource is statically countable");
                    } else if(values!=null)problems.add(name+"#"+methodName+": @ValueSource without @ParameterizedTest");
                    methods.add("{\"name\":"+json(methodName)+",\"kind\":"+json(kind)+",\"invocations\":"+count+"}");
                }
                if(methods.isEmpty())problems.add(name+": selected suite has no supported executable test methods");
                suites.add("{\"name\":"+json(name)+",\"source\":"+json(Path.of(unit.getSourceFile().toUri()).toString())+",\"methods\":["+String.join(",",methods)+"]}");
            }
        }
        for(Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)problems.add("Java syntax error at "+d.getLineNumber()+":"+d.getColumnNumber());
        manager.close();
        System.out.println("{\"suites\":["+String.join(",",suites)+"],\"problems\":["+String.join(",",problems.stream().map(SurefireSourceInventory::json).toList())+"]}");
    }
}
