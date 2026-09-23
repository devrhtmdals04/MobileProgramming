package com.example.helloworld

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.*

/** Cached, lit hemisphere stretched with the same deformation field as its silhouette. */
internal class SoftSurface {
    private val size = 512
    private val divisions = 32
    private val vertices = FloatArray((divisions + 1) * (divisions + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var texture: Bitmap? = null
    private var current: Material? = null

    private fun bake(material: Material): Bitmap {
        val pixels = IntArray(size * size)
        val glossy = material != Material.MOCHI
        val exponent = when (material) { Material.JELLY -> 95f; Material.PUDDING -> 48f; Material.MOCHI -> 12f }
        for (y in 0 until size) for (x in 0 until size) {
            val nx = (x + .5f) / size * 2f - 1f
            val ny = (y + .5f) / size * 2f - 1f
            val rr = nx * nx + ny * ny
            if (rr >= 1f) continue
            val nz = sqrt(1f - rr)
            val diffuse = max(0f, nx * -.42f + ny * -.57f + nz * .70f)
            // Broad softbox reflection and a small bright core, both tied to surface normals.
            val softbox = exp(-((nx+.34f).pow(2)/.052f + (ny+.48f).pow(2)/.016f))
            val spec = max(0f, nx * -.24f + ny * -.32f + nz * .916f).pow(exponent)
            val rim = (1f-nz).pow(3f) * (if (glossy) .28f else .10f)
            val transmitted = exp(-((nx-.32f).pow(2)/.34f + (ny-.64f).pow(2)/.065f)) * (if(glossy) .22f else .06f)
            val grain = if (glossy) 0f else (sin(x*127.1+y*311.7)*.012).toFloat()
            val light = (.20f + diffuse * .80f).coerceIn(0f,1f)
            val shine = (spec * (if(glossy) .85f else .08f) + softbox * (if(glossy) .62f else .02f) + rim + transmitted).coerceIn(0f,.92f)
            fun channel(shift: Int): Int {
                val lo = (material.dark shr shift and 255) / 255f
                val hi = (material.light shr shift and 255) / 255f
                val base = (lo*.74f + (hi-lo*.74f)*light + grain).coerceIn(0f,1f)
                return ((base + (1f-base)*shine)*255).roundToInt().coerceIn(0,255)
            }
            val alpha = ((1f-sqrt(rr))*size*.65f).coerceIn(0f,1f)
            pixels[y*size+x] = Color.argb((alpha*255).toInt(), channel(16), channel(8), channel(0))
        }
        return Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888)
    }

    fun draw(canvas: Canvas, material: Material, map: (Float, Float, FloatArray, Int) -> Unit) {
        if (current != material) {
            // Let Android release the previous bitmap after any queued rendering completes.
            texture = bake(material)
            current = material
        }
        var index = 0
        for (y in 0..divisions) for (x in 0..divisions) {
            map(x*2f/divisions-1f,y*2f/divisions-1f,vertices,index)
            index += 2
        }
        canvas.drawBitmapMesh(texture!!, divisions, divisions, vertices, 0, null, 0, paint)
    }
}
