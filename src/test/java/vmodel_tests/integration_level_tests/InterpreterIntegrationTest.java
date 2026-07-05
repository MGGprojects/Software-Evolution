package vmodel_tests.integration_level_tests;

import ast.ASTUtils;
import interpreter.BabyCobolInterpreter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import preprocessing.BabyCobolParserUtils;
import preprocessing.StopProgramException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class InterpreterIntegrationTest {

    private static final String RESOURCE_PATH = "src/test/java/vmodel_tests/integration_level_tests/resources/";

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private InputStream originalIn;

    @BeforeEach
    public void setUp() {
        originalOut = System.out;
        System.setOut(new PrintStream(outContent));
        originalIn = System.in;
    }

    @AfterEach
    public void tearDown() {
        System.setOut(originalOut);
        System.setIn(originalIn);
    }

    private BabyCobolInterpreter createInterpreter(String filename) throws Exception {
        String source = Files.readString(Paths.get(RESOURCE_PATH + filename));
        String processed = BabyCobolParserUtils.preprocess(source);
        ASTUtils.ASTResult ast = ASTUtils.buildASTAndSymbolTable(processed);
        return new BabyCobolInterpreter(ast.symbolTable);
    }

    private BabyCobolInterpreter runProgram(String filename) throws Exception {
        BabyCobolInterpreter interpreter = createInterpreter(filename);
        String source = Files.readString(Paths.get(RESOURCE_PATH + filename));
        ASTUtils.ASTResult ast = ASTUtils.buildASTAndSymbolTable(BabyCobolParserUtils.preprocess(source));
        try {
            interpreter.execute(ast.root);
        } catch (StopProgramException e) {
            // normal program termination via STOP
        }
        return interpreter;
    }

    private void setMockInput(String... lines) {
        System.setIn(new LineByLineInputStream(lines));
    }

    // Custom InputStream to prevent Scanner buffering issues with ACCEPT statements
    private static class LineByLineInputStream extends InputStream {
        private final String[] lines;
        private int lineIdx = 0, charIdx = 0;
        private boolean lineEnded = false;

        LineByLineInputStream(String... lines) { this.lines = lines; }

        @Override
        public int read() {
            if (lineIdx >= lines.length) return -1;
            String line = lines[lineIdx];
            if (charIdx < line.length()) return line.charAt(charIdx++);
            if (!lineEnded) { lineEnded = true; return '\n'; }
            lineIdx++; charIdx = 0; lineEnded = false;
            return -1;
        }
    }

    @Test
    public void testMemoryAndMoveInitialization() throws Exception {
        BabyCobolInterpreter interpreter = createInterpreter("memory_and_move.babycob");

        Map<String, Object> memoryBefore = interpreter.getMemory();
        assertEquals(0.0, memoryBefore.get("numvar"));
        assertEquals("", memoryBefore.get("strvar"));
        assertEquals(0.0, memoryBefore.get("target1"));
        assertEquals(0.0, memoryBefore.get("target2"));

        String source = Files.readString(Paths.get(RESOURCE_PATH + "memory_and_move.babycob"));
        ASTUtils.ASTResult ast = ASTUtils.buildASTAndSymbolTable(BabyCobolParserUtils.preprocess(source));
        interpreter.execute(ast.root);

        Map<String, Object> memoryAfter = interpreter.getMemory();
        assertEquals(123.0, (Double) memoryAfter.get("numvar"));
        assertEquals("HELLO", memoryAfter.get("strvar"));
        assertEquals(456.0, (Double) memoryAfter.get("target1"));
        assertEquals(456.0, (Double) memoryAfter.get("target2"));
    }

    @Test
    public void testDisplayAndAcceptStatements() throws Exception {
        setMockInput("456", "world");
        BabyCobolInterpreter interpreter = runProgram("display_and_accept.babycob");

        Map<String, Object> memory = interpreter.getMemory();
        assertEquals(456.0, (Double) memory.get("invar"));
        assertEquals("world", memory.get("strvar"));

        String stdout = outContent.toString();
        assertTrue(stdout.contains("ENTER NUM:"));
        assertTrue(stdout.contains("ENTER STR:"));
        assertTrue(stdout.contains("NUM IS 456.0 STR IS world"));
    }

    @Test
    public void testMathOperations() throws Exception {
        Map<String, Object> memory = runProgram("math_operations.babycob").getMemory();
        assertEquals(10.0, (Double) memory.get("a"));
        assertEquals(30.0, (Double) memory.get("b"));
        assertEquals(-25.0, (Double) memory.get("d"));
        assertEquals(2.0 / 30.0, (Double) memory.get("c"), 0.0001);
    }

    @Test
    public void testConditionalAndLoop() throws Exception {
        Map<String, Object> memory = runProgram("conditional_and_loop.babycob").getMemory();
        assertEquals(12.0, (Double) memory.get("x"));
        assertEquals(22.0, (Double) memory.get("y"));
        assertEquals(0.0, (Double) memory.get("counter"));
    }

    @Test
    public void testEvaluateStatement() throws Exception {
        runProgram("evaluate_statement.babycob");
        String stdout = outContent.toString();
        assertTrue(stdout.contains("X IN 10-20 RANGE"));
        assertTrue(stdout.contains("BOTH MATCH"));
        assertTrue(stdout.contains("X IS GREATER THAN 10"));
    }

    @Test
    public void testEvaluateContractedStatement() throws Exception {
        Map<String, Object> memory = runProgram("evaluate_contracted.babycob").getMemory();

        String stdout = outContent.toString();
        // check all branches are visited and COUNTER reaches 8
        assertTrue(stdout.contains("MATCHED OR VALUES"),
                "Should match 10 in WHEN 10 OR 20 OR 30");
        assertTrue(stdout.contains("OTHER CAUGHT"),
                "X=25 should fall through to OTHER");
        assertTrue(stdout.contains("MATCHED THROUGH RANGE OR"),
                "X=15 should match WHEN 10 THROUGH 20 OR 30 THROUGH 40");
        assertTrue(stdout.contains("RANGE OTHER CAUGHT"),
                "X=45 should not match any THROUGH range");
        assertTrue(stdout.contains("ALSO MATCH"),
                "X=5 ALSO Y=25 should match WHEN 5 ALSO 25");
        assertTrue(stdout.contains("TRUE CONDITION MATCH"),
                "TRUE mode with X > 10 AND X < 20 should match when x=12");
        assertTrue(stdout.contains("TRUE OR OTHER"),
                "TRUE mode with X < 5 OR X > 10 should not match when x=8");
        assertTrue(stdout.contains("TRUE OR MATCH"),
                "TRUE mode with X < 5 OR X > 10 should match when x=3");

        // COUNTER should be 8, which is one increment per matching branch
        Double counter = (Double) memory.get("counter");
        assertEquals(8.0, counter, 0.001, "COUNTER should be 8 after all evaluate branches");
    }

    @Test
    public void testPerformStatement() throws Exception {
        Map<String, Object> memory = runProgram("perform_statement.babycob").getMemory();
        // CNT1: PERFORM TASK1 3 TIMES => 3, PERFORM TASK1 THROUGH TASK2 2 TIMES => +2, fallthrough => +1 = 6
        // CNT2: PERFORM TASK1 THROUGH TASK2 2 TIMES => 2, fallthrough => +1 = 3
        assertEquals(6.0, (Double) memory.get("cnt1"));
        assertEquals(3.0, (Double) memory.get("cnt2"));
    }

    @Test
    public void testPerformThroughMissingTarget() {
        String filename = "perform_target_missing.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        assertTrue(exception.getMessage().contains("doesnotexist")
                || exception.getMessage().contains("DOESNOTEXIST"),
            "Error should mention the missing paragraph name");
    }

    @Test
    public void testPerformThroughMissingThrough() {
        String filename = "perform_through_missing.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        assertTrue(exception.getMessage().contains("nonexistent")
                || exception.getMessage().contains("NONEXISTENT"),
            "Error should mention the missing THROUGH paragraph name");
    }

    @Test
    public void testPerformArithmeticMultipleTimes() throws Exception {
        Map<String, Object> memory = runProgram("perform_arithmetic_times.babycob").getMemory();
        // COUNTER: PERFORM ADDONE 5 TIMES => 5, PERFORM ADDONE THROUGH ADDTHREE 3 TIMES => +3 = 8
        //   then fallthrough executes ADDONE => +1 = 9
        // TOTAL: PERFORM ADDONE THROUGH ADDTHREE 3 TIMES => ADDTHREE 3*3 = 9
        //   then fallthrough executes ADDTHREE => +3 = 12
        assertEquals(9.0, (Double) memory.get("counter"), 0.001,
            "COUNTER should be 9: 5 from first PERFORM + 3 from second PERFORM + 1 from fallthrough");
        assertEquals(12.0, (Double) memory.get("total"), 0.001,
            "TOTAL should be 12: 9 from PERFORM THROUGH + 3 from fallthrough");
    }

    // --- Computable GO TO tests ---

    @Test
    public void testGoToComputable() throws Exception {
        runProgram("goto_computable.babycob");
        String stdout = outContent.toString();
        assertTrue(stdout.contains("START"), "Should print START before GO TO");
        assertTrue(stdout.contains("END"), "Should print END after computed GO TO to FINISH");
        assertFalse(stdout.contains("SHOULD NOT PRINT"), "Should skip the DISPLAY after GO TO");
    }

    @Test
    public void testGoToComputableInvalidTarget() {
        String filename = "goto_computable_error.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        assertTrue(exception.getMessage().contains("DOES-NOT-EXIST")
                || exception.getMessage().contains("DOES-NOT-EXIST".toLowerCase())
                || exception.getMessage().contains("does not exist"),
            "Error should mention the invalid runtime target");
    }

    @Test
    public void testGoToBasic() throws Exception {
        runProgram("goto_basic.babycob");
        String stdout = outContent.toString();
        assertTrue(stdout.contains("START"), "Should print START");
        assertTrue(stdout.contains("END"), "Should print END after GO TO FINISH");
        assertFalse(stdout.contains("SHOULD NOT PRINT"), "Should skip the DISPLAY after GO TO");
    }

    // --- ALTER tests ---

    @Test
    public void testAlterBasic() throws Exception {
        runProgram("alter_basic.babycob");
        String stdout = outContent.toString();
        assertTrue(stdout.contains("START"), "Should print START");
        assertTrue(stdout.contains("THIRD"), "ALTER should redirect GO TO FIRST to GO TO THIRD");
        assertTrue(stdout.contains("END"), "Should print END");
        assertFalse(stdout.contains("SECOND"), "SECOND should not be printed because ALTER redirected FIRST to THIRD");
    }

    @Test
    public void testAlterUnknownSource() {
        String filename = "alter_unknown_source.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        assertTrue(exception.getMessage().contains("Unknown paragraph")
                || exception.getMessage().contains("DOESNOTEXIST"),
            "Error should mention the unknown source paragraph");
    }

    @Test
    public void testAlterUnknownTarget() {
        String filename = "alter_unknown_target.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        assertTrue(exception.getMessage().contains("Unknown ALTER target")
                || exception.getMessage().contains("DOESNOTEXIST"),
            "Error should mention the unknown target paragraph");
    }

    // --- SIGNAL tests ---

    @Test
    public void testSignalBasic() throws Exception {
        runProgram("signal_basic.babycob");
        String stdout = outContent.toString();
        assertTrue(stdout.contains("START"), "Should print START");
        assertTrue(stdout.contains("ERROR CAUGHT"), "SIGNAL handler should catch the error");
        assertTrue(stdout.contains("END"), "Should print END after error handler");
        assertFalse(stdout.contains("SHOULD NOT PRINT"), "Should skip the DISPLAY after the GO TO error");
    }

    @Test
    public void testSignalOff() {
        String filename = "signal_off.babycob";
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            runProgram(filename);
        });
        // after SIGNAL OFF the error from GO TO UNKNOWN should propagate as RuntimeException
        String stdout = outContent.toString();
        assertTrue(stdout.contains("SIGNAL ON"), "Should print SIGNAL ON");
        assertTrue(stdout.contains("SIGNAL OFF"), "Should print SIGNAL OFF");
        assertFalse(stdout.contains("ERROR CAUGHT"), "SIGNAL handler should NOT be triggered after SIGNAL OFF");
        assertTrue(exception.getMessage() != null, "RuntimeException should be thrown after SIGNAL OFF disables the handler");
    }
}
