package sternhalma.nnbench;

import org.teavm.jso.JSExport;

/**
 * The surface that TeaVM exports to JavaScript for the network benchmark (both targets, Wasm GC and JS). Every method
 * returns a String ({@code "ok ..."} or {@code "error ..."}); data comes as Base64 (see {@link NetBenchCore}).
 * Bench only: it is not part of the shipped Web facade.
 */
public final class NetBenchWeb {
    private static final NetBenchCore CORE = new NetBenchCore();

    private NetBenchWeb() {
    }

    /** TeaVM requires a main method; the exports are the entry points. */
    public static void main(String[] args) {
    }

    @JSExport
    public static String load(String netBase64) {
        return CORE.load(netBase64);
    }

    @JSExport
    public static String setInputs(String base64, int positions) {
        return CORE.setInputs(base64, positions);
    }

    @JSExport
    public static String run(int batch, int repeats) {
        return CORE.run(batch, repeats);
    }

    @JSExport
    public static String check(int batch) {
        return CORE.check(batch);
    }
}
