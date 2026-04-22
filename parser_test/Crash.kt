import java.io.File
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: kotlinc Crash.kt Main.kt -include-runtime -d crash.jar && java -jar crash.jar <save.bin>")
    val buf = File(path).readBytes()
    println("file size: ${buf.size}")
    val w = Walker(buf)
    val err = runCatching { w.run() }.exceptionOrNull()
    println("parsed=${w.fields.size}, classes=${w.classCounts.size}")
    if (err != null) {
        println("ERROR at pos=${w.pos} (0x${w.pos.toString(16)}): $err")
        err.printStackTrace()
    }
}
