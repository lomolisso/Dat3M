package com.dat3m.dartagnan.litmus;

import com.dat3m.dartagnan.configuration.Arch;
import com.dat3m.dartagnan.configuration.Method;
import com.dat3m.dartagnan.test.ResourceHelper;
import com.dat3m.dartagnan.test.TestHelper;
import com.dat3m.dartagnan.verification.ResultStatus;
import com.dat3m.dartagnan.verification.VerificationTask;
import com.dat3m.dartagnan.verification.VerificationTaskSolver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.dat3m.dartagnan.test.ResourceHelper.getRootPath;
import static com.dat3m.dartagnan.test.TestHelper.EXTENSION_LITMUS;
import static com.dat3m.dartagnan.utils.Utils.hasExtension;
import static org.junit.Assert.assertTrue;

// Runs every test in litmus/PTX/Kamui with the PTX 8.7 model and only checks that the model decides it.
// Tests that use instructions the model does not support yet are listed in PTXv8_7-Kamui-pending.csv.
@RunWith(Parameterized.class)
public class LitmusPTXv8_7KamuiTest extends AbstractLitmusTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Iterable<Object[]> data() throws IOException {
        final Set<Path> pending = Files.readAllLines(ResourceHelper.getTestResourcePath("PTXv8_7-Kamui-pending.csv"))
                .stream()
                .filter(line -> !line.isBlank() && !line.startsWith("//"))
                .map(ResourceHelper::getRootPath)
                .collect(Collectors.toSet());
        try (Stream<Path> files = Files.walk(getRootPath("litmus/PTX/Kamui/"))) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> hasExtension(f, EXTENSION_LITMUS))
                    .filter(f -> !pending.contains(f))
                    .sorted()
                    .map(f -> new Object[]{f})
                    .toList();
        }
    }

    public LitmusPTXv8_7KamuiTest(Path path) {
        super(Arch.PTX, path, null);
    }

    @Override
    protected String getTargetWmmName() { return "ptx-v8.7"; }

    @Override
    protected boolean isLazyMethodEnabled() {
        // FIXME: Some of these give wrong results. See #1039.
        return false;
    }

    @Override
    @Test
    public void testAssume() throws Exception {
        final VerificationTask task = getTaskBuilder().build(TestHelper.parseProgram(getProgramPath()),
                TestHelper.parseWmm(getTargetWmmPath()), getTestedProperties());
        try (VerificationTaskSolver solver = VerificationTaskSolver.createWithMethod(task, Method.EAGER)
                .withShutdownManager(shutdownManager.get())) {
            solver.run();
            final ResultStatus status = solver.getResult().getStatus();
            assertTrue("Undecided: " + status, status == ResultStatus.PASS || status == ResultStatus.FAIL);
        }
    }
}
