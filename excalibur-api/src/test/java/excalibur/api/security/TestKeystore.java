package excalibur.api.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates throwaway keystores for the TLS tests.
 *
 * <p>Generated per run rather than committed. A checked-in keystore is a checked-in private key:
 * harmless in itself, but it trains everyone who copies the pattern, and it is the kind of file
 * that gets reused outside the tests. The JDK ships {@code keytool} beside the JVM running these
 * tests, so generating one costs a subprocess and nothing else.
 *
 * <p>Certificates are valid for one day and carry {@code localhost} and {@code 127.0.0.1} as
 * subject alternative names, which is what a client verifying the hostname will look for.
 */
public final class TestKeystore {

    private TestKeystore() {
        // Test fixture
    }

    /** Creates a keystore holding one self-signed key entry per alias. */
    public static Path create(Path directory, String fileName, String type, String password,
                       String... aliases) throws IOException, InterruptedException {
        Path keystore = directory.resolve(fileName);
        for (String alias : aliases) {
            generate(keystore, type, password, password, alias);
        }
        if (!Files.exists(keystore)) {
            throw new IOException("keytool produced no keystore at " + keystore);
        }
        return keystore;
    }

    /** Creates a keystore whose key entry password differs from the store password. */
    public static Path createWithDistinctKeyPassword(Path directory, String fileName, String type,
                                              String storePassword, String keyPassword,
                                              String alias) throws IOException, InterruptedException {
        Path keystore = directory.resolve(fileName);
        generate(keystore, type, storePassword, keyPassword, alias);
        return keystore;
    }

    private static void generate(Path keystore, String type, String storePassword,
                                 String keyPassword, String alias)
            throws IOException, InterruptedException {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>(List.of(
            keytool.toString(),
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-dname", "CN=localhost, OU=Test, O=ExcaliburDB, C=US",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-keystore", keystore.toString(),
            "-storetype", type,
            "-storepass", storePassword,
            "-keypass", keyPassword));

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        // keytool prompts on stdin for anything it was not given. Closing stdin turns a prompt
        // into an immediate EOF and a non-zero exit, rather than a test that hangs until the
        // build is killed.
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("keytool timed out generating " + alias);
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool failed for alias " + alias + ": " + output);
        }
    }
}
