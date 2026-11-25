import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiPredicate;

import static java.lang.constant.ConstantDescs.CD_void;

// The embedded FancyAnalytics versions lack a public shutdown API.
public class ModelLoggingPatch {
    private static final String LIBRARY = "de/oliver/fancyanalytics/";
    private static final String LOGGER_NAME = LIBRARY + "logger/FancyLogger";
    private static final String REQUEST_NAME = LIBRARY + "sdk/utils/HttpRequest";
    private static final ClassDesc LOGGER = ClassDesc.ofInternalName(LOGGER_NAME);
    private static final ClassDesc LIFECYCLE = ClassDesc.of(
            "com.fancyinnovations.fancynpcsmodel.main.ModelLoggingLifecycle");
    private static final ClassDesc EXECUTOR = ClassDesc.of("java.util.concurrent.ExecutorService");
    private static final ClassDesc SCHEDULED = ClassDesc.of("java.util.concurrent.ScheduledExecutorService");
    private static final ClassDesc HTTP = ClassDesc.of("java.net.http.HttpClient");
    private static final ClassDesc RUNTIME = ClassDesc.of("java.lang.Runtime");
    private static final ClassDesc THREAD = ClassDesc.of("java.lang.Thread");

    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toAbsolutePath();
        Path staged = Files.createTempFile(artifact.getParent(), "model-logging-", ".jar");
        try {
            Files.copy(artifact, staged, StandardCopyOption.REPLACE_EXISTING);
            try (var zip = FileSystems.newFileSystem(staged)) {
                patch(zip.getPath("/" + LOGGER_NAME + ".class"), (code, element) -> {
                    if (element instanceof InvokeInstruction invoke
                            && invoke.opcode() == Opcode.INVOKEVIRTUAL
                            && invoke.owner().asInternalName().equals("java/lang/Runtime")
                            && invoke.name().equalsString("addShutdownHook")
                            && invoke.type().equalsString("(Ljava/lang/Thread;)V")) {
                        code.getstatic(LOGGER, "executor", EXECUTOR);
                        code.invokestatic(LIFECYCLE, "registerLogger", MethodTypeDesc.of(CD_void, RUNTIME, THREAD, EXECUTOR));
                        return true;
                    }
                    return false;
                });
                capture(zip.getPath("/" + LIBRARY + "api/FancyAnalyticsAPI.class"), "EXECUTOR", SCHEDULED, "captureExecutor", EXECUTOR);
                capture(zip.getPath("/" + LIBRARY + "sdk/ApiClient.class"), "EXECUTOR", SCHEDULED, "captureExecutor", EXECUTOR);
                moveHttpClient(zip.getPath("/" + REQUEST_NAME + ".class"));
            }
            Files.move(staged, artifact, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void capture(Path entry, String field, ClassDesc fieldType, String method, ClassDesc parameter)
            throws Exception {
        patch(entry, (code, element) -> {
            if (element instanceof FieldInstruction store && store.opcode() == Opcode.PUTSTATIC
                    && store.name().equalsString(field) && store.type().equalsString(fieldType.descriptorString())) {
                code.dup().invokestatic(LIFECYCLE, method, MethodTypeDesc.of(CD_void, parameter));
                code.with(store);
                return true;
            }
            return false;
        });
    }

    private static void patch(Path entry, BiPredicate<CodeBuilder, CodeElement> replace) throws Exception {
        patch(entry, "<clinit>", replace);
    }

    private static void moveHttpClient(Path entry) throws Exception {
        // Drop the SDK's static client initialization and route its sole read to the lifecycle.
        boolean[] building = {false};
        patch(entry, "<clinit>", (code, element) -> {
            if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESTATIC
                    && invoke.owner().asInternalName().equals("java/net/http/HttpClient")
                    && invoke.name().equalsString("newBuilder")) {
                building[0] = true;
            }
            if (!building[0] || !(element instanceof Instruction)) return false;
            if (element instanceof FieldInstruction field && clientField(field, Opcode.PUTSTATIC)) {
                building[0] = false;
            }
            return true;
        }, 8);
        patch(entry, "send", (code, element) -> {
            if (element instanceof FieldInstruction field && clientField(field, Opcode.GETSTATIC)) {
                code.invokestatic(LIFECYCLE, "analyticsHttpClient", MethodTypeDesc.of(HTTP));
                return true;
            }
            return false;
        });
    }

    private static boolean clientField(FieldInstruction field, Opcode opcode) {
        return field.opcode() == opcode && field.owner().asInternalName().equals(REQUEST_NAME)
                && field.name().equalsString("client") && field.type().equalsString(HTTP.descriptorString());
    }

    private static void patch(Path entry, String method, BiPredicate<CodeBuilder, CodeElement> replace) throws Exception {
        patch(entry, method, replace, 1);
    }

    private static void patch(Path entry, String method, BiPredicate<CodeBuilder, CodeElement> replace, int expected)
            throws Exception {
        var classFile = ClassFile.of();
        var model = classFile.parse(Files.readAllBytes(entry));
        int[] sites = {0};
        CodeTransform transform = (code, element) -> {
            if (replace.test(code, element)) {
                sites[0]++;
            } else {
                code.with(element);
            }
        };
        byte[] patched = classFile.transformClass(model, ClassTransform.transformingMethodBodies(
                body -> body.methodName().equalsString(method), transform));
        if (sites[0] != expected) {
            throw new IllegalStateException("Expected " + expected + " replacements in " + entry + "#" + method + ", got " + sites[0]);
        }
        Files.write(entry, patched);
    }
}
