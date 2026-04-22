package com.fingerthegame.app.util

import java.io.File

/**
 * Abstract IO surface so the editors don't bake `ShizukuExec` calls in
 * directly. Lets tests inject a fake, lets future builds swap in a
 * SAF-based or root-based backend without rewriting every screen.
 *
 * v2.0 ships with [ShizukuStorage] only — the legacy direct-`ShizukuExec`
 * call sites in [com.fingerthegame.app.screens.FileViewerScreen] etc. are
 * left alone for now and will migrate over a few releases.
 */
interface SaveStorage {
    fun read(path: String): ByteArray
    fun write(path: String, data: ByteArray)
    fun forceStop(pkg: String)
    fun statSize(path: String): Long
    fun validateWritePath(path: String, expectedPkg: String)
    fun backupLocal(originalPath: String, data: ByteArray, localDir: File): File
}

/** Production implementation backed by Shizuku's shell-uid bridge. */
object ShizukuStorage : SaveStorage {
    override fun read(path: String) = ShizukuExec.readFile(path)
    override fun write(path: String, data: ByteArray) = ShizukuExec.writeFile(path, data)
    override fun forceStop(pkg: String) = ShizukuExec.forceStop(pkg)
    override fun statSize(path: String) = ShizukuExec.statSize(path)
    override fun validateWritePath(path: String, expectedPkg: String) =
        ShizukuExec.validateWritePath(path, expectedPkg)
    override fun backupLocal(originalPath: String, data: ByteArray, localDir: File): File =
        ShizukuExec.backupLocal(originalPath, data, localDir)
}

/** Currently-active storage. Swap by writing a different value here from
 *  test setup or a future settings flag. */
var currentStorage: SaveStorage = ShizukuStorage
