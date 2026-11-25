import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.CD_boolean;

// Run with JDK 25 and Gradle's lib/* on the classpath after shading.
public class FancyNpcsShadedPatch {
    private static final String ANALYTICS = "de/oliver/fancyanalytics/";
    private static final String FANCY_LOGGER = ANALYTICS + "logger/FancyLogger";
    private static final String API = ANALYTICS + "api/FancyAnalyticsAPI";
    private static final String SDK = ANALYTICS + "sdk/ApiClient";
    private static final String HTTP_REQUEST = ANALYTICS + "sdk/utils/HttpRequest";
    private static final String CLOUD_REGISTRATION = "de/oliver/cloud/bukkit/BukkitPluginRegistrationHandler";
    private static final ClassDesc HOT_UNLOAD = ClassDesc.of("de.oliver.fancynpcs.HotUnload");
    private static final ClassDesc MAP = ClassDesc.of("java.util.Map");
    private static final String CLEANUP_NAME = "de/oliver/fancynpcs/utils/FancyAnalyticsCleanup";
    private static final ClassDesc CLEANUP = ClassDesc.ofInternalName(CLEANUP_NAME);
    private static final ClassDesc RUNTIME = ClassDesc.of("java.lang.Runtime");
    private static final ClassDesc THREAD = ClassDesc.of("java.lang.Thread");
    private static final ClassDesc EXECUTOR = ClassDesc.of("java.util.concurrent.ExecutorService");
    private static final ClassDesc SCHEDULED_EXECUTOR = ClassDesc.of("java.util.concurrent.ScheduledExecutorService");
    private static final ClassDesc HTTP_CLIENT = ClassDesc.of("java.net.http.HttpClient");
    private static final ClassDesc HTTP_BUILDER = ClassDesc.of("java.net.http.HttpClient$Builder");
    private static final String HTTP_SEND = "()Ljava/net/http/HttpResponse;";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java FancyNpcsShadedPatch.java <shaded FancyNpcs JAR>");
        }
        Path jarPath = Path.of(args[0]).toAbsolutePath();
        Path output = Files.createTempFile(jarPath.getParent(), "fancynpcs-shaded-patch-", ".jar");
        try {
            patchJar(jarPath, output);
            Files.move(output, jarPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static void patchJar(Path jarPath, Path output) throws Exception {
        try (var jar = ZipFile.builder().setPath(jarPath).get()) {
            requiredEntry(jar, CLEANUP_NAME + ".class");
            var transforms = Map.of(
                    FANCY_LOGGER, captureLoggerHook(),
                    API, captureField(API, "EXECUTOR", SCHEDULED_EXECUTOR, "captureApiExecutor", EXECUTOR),
                    SDK, captureField(SDK, "EXECUTOR", SCHEDULED_EXECUTOR, "captureSdkExecutor", EXECUTOR),
                    CLOUD_REGISTRATION, batchCommandRefresh()
            );
            var patched = new HashMap<String, byte[]>();
            for (var target : transforms.entrySet()) {
                String name = target.getKey() + ".class";
                try (var input = jar.getInputStream(requiredEntry(jar, name))) {
                    patched.put(name, patch(input.readAllBytes(), target.getKey(), target.getValue()));
                }
            }
            String httpEntry = HTTP_REQUEST + ".class";
            try (var input = jar.getInputStream(requiredEntry(jar, httpEntry))) {
                patched.put(httpEntry, patchHttpRequest(input.readAllBytes()));
            }

            // The baseline includes duplicate, unrelated entries; a JDK ZIP rewrite drops or rejects them.
            try (var out = new ZipArchiveOutputStream(output)) {
                var entries = jar.getEntries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    byte[] replacement = patched.get(entry.getName());
                    if (replacement == null) {
                        try (var raw = jar.getRawInputStream(entry)) {
                            out.addRawArchiveEntry(entry, raw);
                        }
                    } else {
                        var updated = new ZipArchiveEntry(entry.getName());
                        updated.setTime(entry.getTime());
                        out.putArchiveEntry(updated);
                        out.write(replacement);
                        out.closeArchiveEntry();
                    }
                }
            }
        }
    }

    private static ZipArchiveEntry requiredEntry(ZipFile jar, String name) {
        var entries = jar.getEntries(name).iterator();
        if (!entries.hasNext()) {
            throw new IllegalStateException("Missing entry: " + name);
        }
        var entry = entries.next();
        if (entries.hasNext()) {
            throw new IllegalStateException("Ambiguous entry: " + name);
        }
        return entry;
    }

    private static byte[] patch(byte[] original, String owner, CodeTransform transform) {
        var classFile = ClassFile.of();
        var model = classFile.parse(original);
        if (!model.thisClass().asInternalName().equals(owner)) {
            throw new IllegalStateException("Unexpected class at " + owner);
        }
        String methodName = owner.equals(CLOUD_REGISTRATION) ? "unregisterRootCommand" : "<clinit>";
        String descriptor = owner.equals(CLOUD_REGISTRATION) ? "(Lde/oliver/cloud/component/CommandComponent;)V" : "()V";
        if (model.methods().stream().noneMatch(method -> method.methodName().equalsString(methodName)
                && method.methodType().equalsString(descriptor) && method.code().isPresent())) {
            throw new IllegalStateException("Missing " + methodName + descriptor + " for " + owner);
        }
        if (owner.equals(CLOUD_REGISTRATION) && model.fields().stream().noneMatch(field ->
                field.fieldName().equalsString("registeredCommands") && field.fieldType().equalsString(MAP.descriptorString())
                        && !field.flags().has(AccessFlag.STATIC))) {
            throw new IllegalStateException("Unexpected Cloud command registry field");
        }
        if (owner.equals(FANCY_LOGGER) && model.fields().stream().noneMatch(field ->
                field.fieldName().equalsString("executor") && field.fieldType().equalsString(EXECUTOR.descriptorString())
                        && field.flags().has(AccessFlag.STATIC))) {
            throw new IllegalStateException("Unexpected FancyLogger executor field");
        }
        return classFile.transformClass(model, ClassTransform.transformingMethodBodies(
                method -> method.methodName().equalsString(methodName) && method.methodType().equalsString(descriptor), transform));
    }

    static byte[] patchHttpRequest(byte[] original) {
        var classFile = ClassFile.of();
        var model = classFile.parse(original);
        if (!model.thisClass().asInternalName().equals(HTTP_REQUEST)
                || model.fields().stream().filter(FancyNpcsShadedPatch::isHttpClientField).count() != 1) {
            throw new IllegalStateException("Unexpected FancyAnalytics HttpRequest client field");
        }

        int reads = 0;
        int writes = 0;
        for (var method : model.methods()) {
            if (method.code().isEmpty()) {
                continue;
            }
            Instruction previous = null;
            for (var element : method.code().orElseThrow()) {
                if (element instanceof FieldInstruction field && isHttpClientAccess(field)) {
                    if (field.opcode() == Opcode.GETSTATIC && method.methodName().equalsString("send")
                            && method.methodType().equalsString(HTTP_SEND)) {
                        reads++;
                    } else if (field.opcode() == Opcode.PUTSTATIC && method.methodName().equalsString("<clinit>")
                            && method.methodType().equalsString("()V") && isHttpClientBuild(previous)) {
                        writes++;
                    } else {
                        throw new IllegalStateException("Unexpected HttpRequest.client use in " + method.methodName());
                    }
                }
                if (element instanceof Instruction instruction) {
                    previous = instruction;
                }
            }
        }
        if (reads != 1 || writes != 1) {
            throw new IllegalStateException("Expected one HttpRequest.client read and write; found " + reads + "/" + writes);
        }

        var dropClient = ClassTransform.dropping(element -> element instanceof FieldModel field && isHttpClientField(field));
        var initialize = ClassTransform.transformingMethodBodies(method -> method.methodName().equalsString("<clinit>"),
                singleSite("HttpRequest.client build", (builder, element) -> {
                    if (isHttpClientBuild(element)) {
                        builder.invokestatic(CLEANUP, "initializeHttpClient", MethodTypeDesc.of(CD_void, HTTP_BUILDER));
                        return true;
                    }
                    return false;
                }).andThen((builder, element) -> {
                    if (!(element instanceof FieldInstruction field && isHttpClientAccess(field))) {
                        builder.with(element);
                    }
                }));
        var acquire = ClassTransform.transformingMethodBodies(method -> method.methodName().equalsString("send")
                && method.methodType().equalsString(HTTP_SEND), singleSite("HttpRequest.client read", (builder, element) -> {
                    if (element instanceof FieldInstruction field && isHttpClientAccess(field)) {
                        builder.invokestatic(CLEANUP, "httpClient", MethodTypeDesc.of(HTTP_CLIENT));
                        return true;
                    }
                    return false;
                }));
        return classFile.transformClass(model, dropClient.andThen(initialize).andThen(acquire));
    }

    private static boolean isHttpClientField(FieldModel field) {
        return field.fieldName().equalsString("client") && field.fieldType().equalsString(HTTP_CLIENT.descriptorString())
                && field.flags().has(AccessFlag.PRIVATE) && field.flags().has(AccessFlag.STATIC)
                && field.flags().has(AccessFlag.FINAL);
    }

    private static boolean isHttpClientAccess(FieldInstruction field) {
        return field.owner().asInternalName().equals(HTTP_REQUEST) && field.name().equalsString("client")
                && field.type().equalsString(HTTP_CLIENT.descriptorString());
    }

    private static boolean isHttpClientBuild(CodeElement element) {
        return element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKEINTERFACE
                && invoke.owner().asSymbol().equals(HTTP_BUILDER)
                && invoke.name().equalsString("build")
                && invoke.type().equalsString(MethodTypeDesc.of(HTTP_CLIENT).descriptorString());
    }

    private static CodeTransform batchCommandRefresh() {
        return singleSite("Cloud per-root command refresh", (builder, element) -> {
            if (!(element instanceof InvokeInstruction invoke) || invoke.opcode() != Opcode.INVOKESTATIC
                    || !invoke.owner().asInternalName().equals("org/bukkit/Bukkit")
                    || !invoke.name().equalsString("getOnlinePlayers")
                    || !invoke.type().equalsString("()Ljava/util/Collection;")) {
                return false;
            }
            // Cloud already removed this root; do not build player trees between removals.
            var refresh = builder.newLabel();
            var bool = MethodTypeDesc.of(CD_boolean);
            builder.invokestatic(HOT_UNLOAD, "isBatchingCommandRemoval", bool).ifeq(refresh);
            builder.aload(0).getfield(ClassDesc.ofInternalName(CLOUD_REGISTRATION), "registeredCommands", MAP);
            builder.invokeinterface(MAP, "isEmpty", bool).ifne(refresh).return_().labelBinding(refresh);
            builder.with(invoke);
            return true;
        });
    }

    private static CodeTransform captureLoggerHook() {
        return singleSite("FancyLogger shutdown hook", (builder, element) -> {
            if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKEVIRTUAL
                    && invoke.owner().asInternalName().equals("java/lang/Runtime")
                    && invoke.name().equalsString("addShutdownHook")
                    && invoke.type().equalsString("(Ljava/lang/Thread;)V")) {
                builder.getstatic(ClassDesc.ofInternalName(FANCY_LOGGER), "executor", EXECUTOR);
                builder.invokestatic(CLEANUP, "registerLoggerHook", MethodTypeDesc.of(CD_void, RUNTIME, THREAD, EXECUTOR));
                return true;
            }
            return false;
        });
    }

    private static CodeTransform captureField(String owner, String field, ClassDesc type, String capture,
                                              ClassDesc parameterType) {
        return singleSite(capture, (builder, element) -> {
            if (element instanceof FieldInstruction store && store.opcode() == Opcode.PUTSTATIC
                    && store.owner().asInternalName().equals(owner)
                    && store.name().equalsString(field) && store.type().equalsString(type.descriptorString())) {
                builder.dup();
                builder.invokestatic(CLEANUP, capture, MethodTypeDesc.of(CD_void, parameterType));
                builder.with(store);
                return true;
            }
            return false;
        });
    }

    private static CodeTransform singleSite(String site, BiPredicate<CodeBuilder, CodeElement> replace) {
        return new CodeTransform() {
            private int count;

            @Override
            public void accept(CodeBuilder builder, CodeElement element) {
                if (replace.test(builder, element)) {
                    count++;
                } else {
                    builder.with(element);
                }
            }

            @Override
            public void atEnd(CodeBuilder builder) {
                if (count != 1) {
                    throw new IllegalStateException("Expected one " + site + " site; found " + count);
                }
            }
        };
    }
}
