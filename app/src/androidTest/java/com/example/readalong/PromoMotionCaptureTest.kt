package com.example.readalong

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.activity.ComponentActivity
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.data.local.*
import com.example.readalong.data.preferences.*
import com.example.readalong.data.reading.*
import com.example.readalong.ui.reading.*
import com.example.readalong.ui.library.*
import com.example.readalong.ui.player.*
import com.example.readalong.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in filming harness. Production UI, real Media3 playback and real Whistle recognition.
 * Temporary public-domain library only; restores preferences and removes its own rows/files. */
class PromoMotionCaptureTest {
 private val compose = FilmUi()
 @OptIn(ExperimentalSharedTransitionApi::class)
 @Test fun film() = runBlocking {
  org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("promoMotion") == "true")
  val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
  val auto = InstrumentationRegistry.getInstrumentation().uiAutomation
  val db = app.database
  val old = app.appPreferences.preferences.first(); val oldReader = app.readerPreferences.preferences.first();val oldBook = app.bookReaderPreferences.preferences.first()
  val oldSelection = db.bookDao().metadata("current_book")
  val transcribeOnly = InstrumentationRegistry.getArguments().getString("transcribeOnly") == "true"
  val out = File(app.getExternalFilesDir(null),if(transcribeOnly) "promo-transcription" else "promo-motion").apply{mkdirs()}
  val owned = mutableListOf<File>();val ids = (0..5).map{"promo-motion-$it"}
  val models = androidx.lifecycle.ViewModelStore()
  var screen by mutableStateOf("library");var sync by mutableStateOf(false)
  var settings by mutableStateOf(AppPreferences(batteryWarningSeen=true,homeGuideHidden=true,playerGuideHidden=true,readerGuideHidden=true))
  var recording = "";var recordAt=0L
  val events=JSONArray(); val positions=JSONArray()
  var recorder:android.os.ParcelFileDescriptor?=null
  fun mark(name:String, node:FilmNode?=null) {
   compose.waitForIdle()
   val b=node?.fetchSemanticsNode()?.boundsInRoot
   val p=app.playback.state.value
   events.put(JSONObject().apply{put("clip",recording);put("event",name);put("t",(SystemClock.elapsedRealtime()-recordAt)/1000.0);put("positionMs",p.positionMs);put("playing",p.isPlaying);if(b!=null){put("x",b.center.x);put("y",b.center.y)}})
   File(out,"events.json").writeText(events.toString(2))
  }
  fun shot(name:String){ val bmp=auto.takeScreenshot();File(out,"$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle() }
  fun rest(ms:Long){SystemClock.sleep(ms);compose.waitForIdle()}
  fun tap(name:String,node:FilmNode){mark(name,node);node.performTouchInput{click()}}
  fun begin(name:String){recording=name;compose.runOnIdle{sync=true};rest(150);recordAt=SystemClock.elapsedRealtime();recorder=auto.executeShellCommand("screenrecord --bit-rate 18000000 --time-limit 100 /sdcard/Download/promo-$name.mp4");rest(900);compose.runOnIdle{sync=false};mark("sync-end");rest(300)}
  fun end(){mark("end");auto.executeShellCommand("pkill -2 screenrecord").close();rest(1500);recorder?.close();recorder=null}
  fun copy(name:String):File {
   val f=File(File(app.filesDir,"covers").apply{mkdirs()},"promo-motion-$name");owned+=f
   android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand("cat /sdcard/Download/promo-v2/$name")).use{input->f.outputStream().use{input.copyTo(it)}}
   return f
  }
  fun uri(file:File)=FileProvider.getUriForFile(app,"${app.packageName}.files",file).toString()
  val sampleScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  try {
   app.appPreferences.update{settings}
   app.readerPreferences.save(ReaderPreferences(fontFamily="LORA",fontSize=18f,lineSpacing=1.5f,useEpubFonts=false))
   app.bookReaderPreferences.save(ReaderPreferences(fontFamily="LORA",fontSize=18f,lineSpacing=1.5f,theme="LIGHT",useEpubFonts=false))
   val art=listOf("alice.jpg","hail.jpg","dune.jpg","pride.jpg","hobbit.jpg","potter.jpg").map{uri(copy(it))}
   val titles=listOf("Alice's Adventures in Wonderland","Project Hail Mary","Dune","Pride and Prejudice","The Hobbit","Harry Potter and the Sorcerer's Stone")
   val authors=listOf("Lewis Carroll","Andy Weir","Frank Herbert","Jane Austen","J. R. R. Tolkien","J. K. Rowling")
   val entities=(0..5).map{i->BookEntity(ids[i],titles[i],authors[i],art[i],null,105000,100000L-i,false,i,"AVAILABLE")}
   db.bookDao().upsertBooks(entities)
   val audio=uri(copy("alice-opening.wav"))
   db.mediaDao().putAudio(listOf(AudioFileEntity("promo-motion-file",ids[0],audio,"Down the Rabbit-Hole.wav",0,105000,null)))
   db.mediaDao().putChapters(listOf(AudioChapterEntity("promo-motion-chapter","promo-motion-file","Down the Rabbit-Hole",0,0,105000)))
   db.bookDao().insertProgress(listOf(BookProgressEntity(ids[0],23060)))
   val text=listOf(
    Triple(23060L,28800L,"Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do."),
    Triple(28800L,35040L,"Once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it."),
    Triple(35040L,42160L,"And what is the use of a book, thought Alice, without pictures or conversations?"),
    Triple(42160L,60000L,"So she was considering in her own mind, as well as she could, for the hot day made her feel very sleepy and stupid, whether the pleasure of making a daisy-chain would be worth the trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes ran close by her."),
    Triple(60240L,62880L,"There was nothing so very remarkable in that."),
    Triple(62880L,71280L,"Nor did Alice think it so very much out of the way to hear the Rabbit say to itself, Oh dear! Oh dear! I shall be late!"),
    Triple(71280L,79280L,"When she thought it over afterwards, it occurred to her that she ought to have wondered at this, but at the time it all seemed quite natural."),
    Triple(79280L,105000L,"But when the Rabbit actually took a watch out of its waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet, for it flashed across her mind that she had never before seen a rabbit with either a waistcoat-pocket, or a watch to take out of it."))
   db.readingDao().putSegments(text.mapIndexed{i,p->TranscriptEntity("promo-motion-s$i",ids[0],"promo-motion-chapter",p.first,p.second,p.third,1f,null)})
   db.readingDao().putVersion(ReadableVersionEntity(ids[0],"READY",null,1,1,null,System.currentTimeMillis()))
   val epub=File(File(app.cacheDir,"exports").apply{mkdirs()},"promo-motion-alice.epub");owned+=epub
   java.util.zip.ZipOutputStream(epub.outputStream()).use{zip->
    mapOf("mimetype" to "application/epub+zip","META-INF/container.xml" to "<container><rootfile full-path='package.opf'/></container>",
    "package.opf" to "<package><metadata><title>Alice's Adventures in Wonderland</title></metadata><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/></spine></package>",
    "one.xhtml" to "<html><body>${text.joinToString(""){"<p>${it.third}</p>"}}</body></html>").forEach{(p,s)->zip.putNextEntry(java.util.zip.ZipEntry(p));zip.write(s.toByteArray());zip.closeEntry()}}
   app.importedEpub.import(ids[0],android.net.Uri.parse(uri(epub)));app.importedEpub.align(ids[0])
   val books=entities.map{BookWithProgress(it,null).toBook()};val book=books.first().copy(positionMs=23060)
   val reader=ReadingViewModel(ids[0],db,app.transcriptRepository,app.readerPreferences,app.generatedEpub,app.processing,app.importedEpub,app.bookReaderPreferences)
   models.put("motion-reader",reader)
   compose.setContent {
    val ps by app.playback.state.collectAsState()
    CompositionLocalProvider(LocalAppSettings provides settings) { ReadAlongTheme(light=settings.libraryLight) { Surface(Modifier.fillMaxSize()) {
     if(screen=="library") LibraryScreen(LibraryUiState.Ready(books),onRetry={},onAbout={},onBook={app.playback.select(book);screen="player"},
      importControls={IconButton(onClick={}){Icon(painterResource(R.drawable.ic_add),"Add audiobook")}})
     else {
      val layer=rememberGraphicsLayer();val frame=remember{ReaderTransitionFrame(layer)}
      val holder=rememberSaveableStateHolder();val palette=com.example.readalong.ui.components.rememberCoverPalette(book)
      val readingOnly by reader.readOnly.collectAsState(); val bp by reader.bookPreferences.collectAsState()
      val back=if(readingOnly) {if(bp.theme=="DARK")Color.Black else Color.White} else palette.background
      val color by animateColorAsState(if(screen=="follow") back else palette.preview,tween(300))
      val radius by animateDpAsState(if(screen=="follow")0.dp else 24.dp,tween(300))
      SharedTransitionLayout {
       val shared=this
       AnimatedContent(screen,transitionSpec={EnterTransition.None togetherWith ExitTransition.None},label="Expand reader") { mode ->
        val vis=this;val moving=shared.isTransitionActive || vis.transition.currentState!=vis.transition.targetState
        LaunchedEffect(moving){if(!moving)frame.frozen=false}
        val surface=with(shared){Modifier.sharedBounds(rememberSharedContentState("reader:${book.id}"),vis,
         enter=EnterTransition.None,exit=ExitTransition.None,resizeMode=SharedTransitionScope.ResizeMode.RemeasureToBounds,
         zIndexInOverlay=if(mode==screen)1f else 0f,boundsTransform={_,_->tween(300,easing=CubicBezierEasing(.2f,0f,.1f,1f))},
         clipInOverlayDuringTransition=OverlayClip(androidx.compose.foundation.shape.RoundedCornerShape(radius)))}
        CompositionLocalProvider(LocalReaderDrawFrame provides(mode==screen),LocalReaderFrame provides frame,LocalReaderSurface provides surface,
         LocalReaderSurfaceColor provides color,LocalReaderInsets provides ReaderInsets(24.dp,56.dp,24.dp),LocalReaderTransforming provides moving){
         holder.SaveableStateProvider(mode){
          if(mode=="follow")FollowScreen(book,ps,app.playback,reader,{frame.frozen=true;screen="player"})
          else PlayerScreen(book,ps,app.playback,{screen="library"},{},reader,{}, {reader.preloadReader(ps.positionMs);frame.frozen=true;screen="follow"})
         }
        }
       }
      }
     }
     if(sync)Box(Modifier.fillMaxSize().background(Color.Magenta))
    } } }
   }
   sampleScope.launch {while(isActive){val s=app.playback.state.value;synchronized(positions){positions.put(JSONObject().apply{put("clip",recording);put("t",(SystemClock.elapsedRealtime()-recordAt)/1000.0);put("positionMs",s.positionMs);put("playing",s.isPlaying)})};delay(50)}}
   compose.waitForIdle();rest(1000)
   if(!transcribeOnly) {
   begin("flow");mark("library");shot("library");rest(1200)
   compose.onNodeWithTag("library_grid").performTouchInput{swipeUp(durationMillis=700)};mark("library-scroll");rest(1200);shot("library-lower")
   compose.onNodeWithTag("library_grid").performTouchInput{swipeDown(durationMillis=700)};rest(700)
   tap("open-book",compose.onNodeWithTag("book:${ids[0]}"));rest(1600);compose.runOnIdle{app.playback.seek(23060)};rest(400);shot("player")
   tap("cover-flip",compose.onNodeWithTag("flip_cover"));rest(1500);shot("preview")
   tap("expand",compose.onNodeWithContentDescription("Full screen Follow Along"));rest(1000);shot("reader-paused")
   tap("play",compose.onNodeWithContentDescription("Play"));rest(3600)
   // First text tap reveals controls. Second text tap seeks through the real reader callback.
   val target=compose.onNodeWithText("And what is the use",substring=true)
   tap("reveal",target);rest(500);tap("seek-sentence",target);rest(1200);shot("seek-result");rest(6300)
   tap("reveal-mode",target);rest(300);tap("read-only",compose.onNodeWithContentDescription("Read only"));rest(2300);shot("read-only")
   compose.onNodeWithTag("follow_passages").performTouchInput{swipeUp(durationMillis=600)};mark("read-scroll");rest(1500)
   app.bookReaderPreferences.save(ReaderPreferences(fontFamily="LORA",fontSize=18f,lineSpacing=1.5f,theme="DARK",useEpubFonts=false));mark("night-reading");rest(2000);shot("read-only-dark");end()
   // Film real bundled palette/font states using the same library and reader components.
   compose.runOnIdle{screen="library"};rest(600);begin("colors")
   listOf(0xFF496B8A,0xFF287A78,0xFF8A6B25,0xFF955376,0xFF72558C).forEachIndexed{i,c->compose.runOnIdle{settings=settings.copy(basicColor=c,libraryLight=i%2==0)};mark("color-$i");rest(1500);shot("color-$i")};end()
   compose.runOnIdle{screen="follow"};reader.openReader(35040);rest(800);begin("fonts")
   listOf("LORA","GARAMOND","OPEN_SANS").forEachIndexed{i,f->app.bookReaderPreferences.save(ReaderPreferences(fontFamily=f,fontSize=20f,lineSpacing=1.5f,theme="LIGHT",useEpubFonts=false));mark("font-$i");rest(1800);shot("font-$i")};end()
   }
   // Clear only this temporary book's prepared text, then invoke the real on-device engine.
   compose.runOnIdle{app.playback.select(book);screen="player"};app.playback.pause();rest(1000)
   app.importedEpub.remove(ids[0]);db.readingDao().clearTranscript(ids[0]);
   begin("transcribe");mark("before-transcribe");app.processing.rebuild(ids[0]);mark("start-transcribe");rest(1500);compose.onNodeWithTag("library_grid").performTouchInput{swipeUp(durationMillis=650)};mark("progress-visible");rest(15000);shot("transcribe");end()
  } finally {
   if(recorder!=null)end();sampleScope.cancel();File(out,"positions.json").writeText(positions.toString())
   app.processing.cancel(ids[0]);app.playback.releaseBook(ids[0]);compose.runOnIdle{models.clear()}
   app.importedEpub.remove(ids[0]);ids.forEach{db.bookDao().removeBook(it)}
   app.appPreferences.update{old};app.readerPreferences.save(oldReader);app.bookReaderPreferences.save(oldBook)
   if(oldSelection!=null)db.bookDao().putMetadata(LibraryMetadataEntity("current_book",oldSelection))
   owned.forEach{it.delete()};compose.close()
  }
 }
}


/** Use the platform Choreographer, not Compose's virtual test clock, when filming motion. */
private class FilmUi {
 val inst=InstrumentationRegistry.getInstrumentation()
 var scenario:ActivityScenario<ComponentActivity>?=null
 fun setContent(content:@Composable ()->Unit){scenario=ActivityScenario.launch(ComponentActivity::class.java);scenario!!.onActivity{it.setContent(content=content)}}
 fun runOnIdle(block:()->Unit){inst.runOnMainSync(block);inst.waitForIdleSync()}
 fun waitForIdle(){inst.waitForIdleSync()}
 fun close(){scenario?.close()}
 fun onNodeWithTag(tag:String)=FilmNode(this,tag=tag)
 fun onNodeWithContentDescription(value:String)=FilmNode(this,desc=value)
 fun onNodeWithText(value:String,substring:Boolean=false)=FilmNode(this,text=value)
}
private class FilmNode(val ui:FilmUi,val tag:String?=null,val desc:String?=null,val text:String?=null){
 class Bounds(val boundsInRoot:Rect)
 fun fetchSemanticsNode():Bounds {
  if(tag=="library_grid" || tag=="follow_passages")return Bounds(Rect(0f,300f,1080f,1900f))
  if(tag?.startsWith("book:")==true)return Bounds(Rect(65f,540f,505f,1030f))
  if(tag=="flip_cover")return Bounds(Rect(170f,320f,910f,1060f))
  fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
   if(n==null)return null
   if(desc!=null && n.contentDescription?.toString()==desc || text!=null && n.text?.toString()?.contains(text)==true)return n
   for(i in 0 until n.childCount){val found=find(n.getChild(i));if(found!=null)return found};return null
  }
  val until=SystemClock.elapsedRealtime()+6000
  while(SystemClock.elapsedRealtime()<until){val node=find(ui.inst.uiAutomation.rootInActiveWindow);if(node!=null){val b=android.graphics.Rect();node.getBoundsInScreen(b);return Bounds(Rect(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat()))};SystemClock.sleep(50)}
  error("No accessible node: $desc $text")
 }
 fun performTouchInput(block:FilmTouch.()->Unit){FilmTouch(this).block()}
}
private class FilmTouch(val node:FilmNode){
 fun send(action:Int,x:Float,y:Float,down:Long){val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);node.ui.inst.uiAutomation.injectInputEvent(e,true);e.recycle()}
 fun click(){val p=node.fetchSemanticsNode().boundsInRoot.center;val t=SystemClock.uptimeMillis();send(0,p.x,p.y,t);SystemClock.sleep(65);send(1,p.x,p.y,t)}
 fun swipeUp(durationMillis:Long)=swipe(1550f,570f,durationMillis)
 fun swipeDown(durationMillis:Long)=swipe(570f,1550f,durationMillis)
 fun swipe(y0:Float,y1:Float,ms:Long){val t=SystemClock.uptimeMillis();send(0,540f,y0,t);val n=(ms/16).toInt();for(i in 1..n){SystemClock.sleep(16);send(2,540f,y0+(y1-y0)*i/n,t)};send(1,540f,y1,t)}
}
