package ayx.whatsapp

/** Thin JNI layer over the embedded Node.js runtime. */
object NodeBridge {
    init {
        System.loadLibrary("node")        // digidem libnode.so (from CI)
        System.loadLibrary("node_bridge") // our JNI glue (CMake)
    }

    external fun nativeSetenv(key: String, value: String)

    /** Blocks for the life of the node process — always call on a background thread. */
    external fun nativeStart(args: Array<String>, workdir: String): Int
}
