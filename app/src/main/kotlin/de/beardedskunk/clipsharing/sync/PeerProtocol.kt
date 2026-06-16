package de.beardedskunk.clipsharing.sync

import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Symmetrisches Stream-Protokoll fuer einen Sync ueber einen verbundenen Socket.
 *
 * Ablauf (eine Verbindung gleicht beide Seiten ab):
 *  1. Initiator sendet seinen Versions-Vektor (VV-Block).
 *  2. Antwortender sendet die dem Initiator fehlenden Ops, danach seinen eigenen VV.
 *  3. Initiator speist die Ops ein und sendet die dem Antwortenden fehlenden Ops.
 *  4. Antwortender speist diese ein.
 *
 * Nachrichtenrahmen sind zeilenbasiert; Ops sind base64-kodierte Einzelzeilen
 * (siehe [OpCodec.encodeOpLine]).
 */
object PeerProtocol {

    /** Seite, die die Verbindung aufgebaut hat (Initiator). */
    fun runInitiator(local: OpSource, reader: BufferedReader, writer: BufferedWriter): SyncResult {
        writeVv(writer, local.versionVector())
        val incoming = readOps(reader)
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        val remoteVv = readVv(reader)
        val toRemote = local.missingFor(remoteVv)
        writeOps(writer, toRemote)
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    /** Seite, die die Verbindung angenommen hat (Antwortender). */
    fun runResponder(local: OpSource, reader: BufferedReader, writer: BufferedWriter): SyncResult {
        val remoteVv = readVv(reader)
        val toRemote = local.missingFor(remoteVv)
        writeOps(writer, toRemote)
        writeVv(writer, local.versionVector())
        val incoming = readOps(reader)
        var pulled = 0
        for (op in incoming) if (local.ingestOp(op)) pulled++
        return SyncResult(pulled = pulled, pushed = toRemote.size)
    }

    // -------------------------------------------------------------- Rahmen

    private fun writeVv(w: BufferedWriter, vv: Map<String, Long>) {
        w.write("VV"); w.newLine()
        for ((device, seq) in vv) { w.write("$device $seq"); w.newLine() }
        w.write("END"); w.newLine()
        w.flush()
    }

    private fun readVv(r: BufferedReader): Map<String, Long> {
        val header = r.readLine() ?: error("Verbindung abgebrochen (VV erwartet)")
        require(header == "VV") { "VV erwartet, war: $header" }
        val out = HashMap<String, Long>()
        while (true) {
            val line = r.readLine() ?: error("Verbindung abgebrochen (END erwartet)")
            if (line == "END") break
            if (line.isBlank()) continue
            val sp = line.lastIndexOf(' ')
            if (sp > 0) out[line.substring(0, sp)] = line.substring(sp + 1).toLong()
        }
        return out
    }

    private fun writeOps(w: BufferedWriter, ops: List<OpDto>) {
        w.write("OPS"); w.newLine()
        w.write(ops.size.toString()); w.newLine()
        for (op in ops) { w.write(OpCodec.encodeOpLine(op)); w.newLine() }
        w.flush()
    }

    private fun readOps(r: BufferedReader): List<OpDto> {
        val header = r.readLine() ?: error("Verbindung abgebrochen (OPS erwartet)")
        require(header == "OPS") { "OPS erwartet, war: $header" }
        val count = (r.readLine() ?: error("Anzahl erwartet")).toInt()
        val out = ArrayList<OpDto>(count)
        repeat(count) {
            val line = r.readLine() ?: error("Verbindung abgebrochen (Op erwartet)")
            out += OpCodec.decodeOpLine(line)
        }
        return out
    }
}
