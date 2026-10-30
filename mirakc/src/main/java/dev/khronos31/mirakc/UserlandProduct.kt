package dev.khronos31.mirakc

import java.io.File

internal enum class UserlandProduct(
    val label: String,
    val statusProduct: String,
    val daemonLibrary: String,
    val controlLibrary: String,
    val tsLibrary: String,
    val adapterLibrary: String
) {
    PIX("PIX", "pix-userland", "libpixd.so", "libpixctl.so", "libpix-ts.so", "libmirakc-pix-adapter.so"),
    ASICEN("ASICEN", "asicen-userland", "libasicend.so", "libasicenctl.so", "libasicen-ts.so", "libmirakc-asicen-adapter.so")
}

internal data class UserlandTuner(
    val name: String,
    val receiver: Int,
    val systems: Set<BroadcastSystem>
)

internal data class UserlandGeneration(
    val product: UserlandProduct,
    val instanceToken: String,
    val runtimeDir: File,
    val epoch: String,
    val deviceKeys: Set<String>,
    val tuners: List<UserlandTuner>
) {
    fun adapterArguments(receiver: Int): List<String> = listOf(
        "--instance=$instanceToken",
        "--runtime-dir=${runtimeDir.absolutePath}",
        "--receiver=$receiver"
    )
}

internal fun userlandTunersFromInventory(
    product: UserlandProduct,
    instanceToken: String,
    inventory: UserlandStatusInventory
): List<UserlandTuner> {
    require(inventory.product == product.statusProduct && inventory.instance == instanceToken && inventory.ready)
    return inventory.receivers.orEmpty().map { receiver ->
        UserlandTuner("${product.label}-${instanceToken}-${receiver.id}", receiver.id, receiver.systems)
    }
}

internal fun userlandAdapterArguments(
    product: UserlandProduct,
    libraryDirectory: File,
    generation: UserlandGeneration,
    receiver: Int
): List<String> = listOf(
    "${File(libraryDirectory, product.adapterLibrary).absolutePath}",
    "--ts-program=${File(libraryDirectory, product.tsLibrary).absolutePath}"
) + generation.adapterArguments(receiver)

internal fun renderUserlandTunerCommands(
    libraryDirectory: File,
    generations: List<UserlandGeneration>
): String = buildString {
    generations.forEach { generation ->
        generation.tuners.forEach { tuner ->
            val types = buildList {
                if (BroadcastSystem.ISDB_T in tuner.systems) add("GR")
                if (BroadcastSystem.ISDB_S in tuner.systems) {
                    add("BS")
                    add("CS")
                }
            }
            append("  - name: ${tuner.name}\n")
            append("    types: [${types.joinToString(", ")}]\n")
            append("    decoded: true\n")
            val args = userlandAdapterArguments(generation.product, libraryDirectory, generation, tuner.receiver)
            val command = buildList {
                add(shellWord(args.first()))
                add("--ts-program=" + shellWord(args[1].substringAfter('=')))
                args.drop(2).forEach { add(shellWord(it)) }
                add("--channel={{{channel}}}")
                if (BroadcastSystem.ISDB_S in tuner.systems) add("{{{extra_args}}}")
            }.joinToString(" ")
            append("    command: \"")
            append(command.replace("\\", "\\\\").replace("\"", "\\\""))
            append("\"\n")
        }
    }
}

private fun shellWord(value: String): String = "'" + value.replace("'", "'\\''") + "'"
