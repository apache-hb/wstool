package wstool.test.components;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Reads the current computer model from Linux DMI or the Windows host. */
public final class MachineModel {
    private MachineModel() {}

    public static String current() {
        String productName = readProductName();
        return productName.isBlank() ? readWindowsModel() : productName;
    }

    private static String readProductName() {
        Path productName = Path.of("/sys/class/dmi/id/product_name");
        try {
            return Files.readString(productName, StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            return "";
        }
    }

    private static String readWindowsModel() {
        Process process;
        try {
            process =
                    new ProcessBuilder(
                                    "powershell.exe",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-Command",
                                    "(Get-CimInstance Win32_ComputerSystem).Model")
                            .redirectErrorStream(true)
                            .start();
        } catch (IOException exception) {
            return "";
        }

        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException exception) {
            return "";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
