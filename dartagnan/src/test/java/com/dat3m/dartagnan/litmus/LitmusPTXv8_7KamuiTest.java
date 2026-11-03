package com.dat3m.dartagnan.litmus;

import com.dat3m.dartagnan.configuration.Arch;
import com.dat3m.dartagnan.test.ResourceHelper;
import com.dat3m.dartagnan.verification.ResultStatus;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static com.dat3m.dartagnan.test.ResourceHelper.getRootPath;
import static com.dat3m.dartagnan.test.TestHelper.EXTENSION_LITMUS;
import static com.dat3m.dartagnan.utils.Utils.hasExtension;

// Runs every test in litmus/PTX/Kamui with the PTX 8.7 model (program_spec) and compares the result with
// PTXv8_7-Kamui-expected.csv. Every test must have an entry.
@RunWith(Parameterized.class)
public class LitmusPTXv8_7KamuiTest extends AbstractLitmusTest {

    @Parameterized.Parameters(name = "{index}: {0}, {1}")
    public static Iterable<Object[]> data() throws IOException {
        final Map<Path, ResultStatus> expected = ResourceHelper.parseExpectedResults(
                ResourceHelper.getTestResourcePath("PTXv8_7-Kamui-expected.csv"), ResourceHelper::getRootPath);
        try (Stream<Path> files = Files.walk(getRootPath("litmus/PTX/Kamui/"))) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> hasExtension(f, EXTENSION_LITMUS))
                    .sorted()
                    .map(f -> {
                        if (!expected.containsKey(f)) {
                            throw new IllegalStateException("No expected result for " + f);
                        }
                        return new Object[]{f, expected.get(f)};
                    })
                    .toList();
        }
    }

    public LitmusPTXv8_7KamuiTest(Path path, ResultStatus expected) {
        super(Arch.PTX, path, expected);
    }

    @Override
    protected String getTargetWmmName() { return "ptx-v8.7"; }

    @Override
    protected boolean isLazyMethodEnabled() {
        // FIXME: Some of these give wrong results. See #1039.
        return false;
    }
}
