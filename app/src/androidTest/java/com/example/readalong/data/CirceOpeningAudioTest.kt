package com.example.readalong.data

import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ReadAlongApplication
import com.example.readalong.data.transcription.AudioDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class CirceOpeningAudioTest {
    @Test fun decoderAndRealPlayerUseTheSameAudioClockAtReportedEarlySentences() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateCirceOpening")=="true")
        val app=ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(app.cacheDir,"exports/circe-clock-${System.nanoTime()}").apply {mkdirs()}
        fun copy(path:String):File {
            val f=File(directory,path.substringAfterLast('/'))
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/readalong-early-tests/$path")).use { input -> f.outputStream().use {input.copyTo(it,65536)} }
            return f
        }
        fun milliseconds(f:File):FloatArray {
            val b=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(b.remaining()/16) { var sum=0f;repeat(16){sum+=b.get()};sum/16 }
        }
        fun compare(actual:FloatArray,reference:FloatArray,base:Int,label:String) {
            var best=-1.0;var lag=0
            for(shift in -100..100){
                var aa=0.0;var bb=0.0;var ab=0.0
                for(n in 300 until 1300){val a=actual[n].toDouble();val b=reference[base+n+shift].toDouble();aa+=a*a;bb+=b*b;ab+=a*b}
                val score=ab/sqrt(aa*bb);if(score>best){best=score;lag=shift}
            }
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("circe_clock", "$label correlation=$best lag=${lag}ms")})
            assertTrue("$label plays the independently verified audio",best>.6)
        }
        try {
            val audio=copy("circe-original.mp3");val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",audio)
            for (start in listOf(120_000L,150_000L,180_000L)) {
                val reference=milliseconds(copy("circe/${start/1000}.pcm"))
                val output=File(directory,"decoded.pcm")
                AudioDecoder(app).decode(uri,start,30_000,output)
                compare(milliseconds(output),reference,0,"decoder $start")
                val onsets=when(start){120_000L->listOf(129760L,144480L);150_000L->listOf(151280L,155840L,160560L);else->listOf(188720L)}
                for(onset in onsets){val seek=onset-160;compare(capturePlaybackAudio(app,uri,seek),reference,(seek-start).toInt(),"sentence $onset")}
            }
        }finally{directory.deleteRecursively()}
    }
}
