package com.example.readalong.ui.reading

import androidx.lifecycle.*
import androidx.room.withTransaction
import com.example.readalong.data.local.TranscriptCorrectionEntity
import com.example.readalong.data.processing.progress
import androidx.paging.*
import com.example.readalong.data.preferences.*
import com.example.readalong.data.reading.toSegment
import com.example.readalong.data.epub.toReadingSegment
import com.example.readalong.data.epub.useForReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.readalong.data.local.ReadingProgressEntity
import com.example.readalong.data.local.TranscriptChapter
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.reading.TranscriptRepository
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.sentenceCues
import com.example.readalong.domain.reading.sentenceAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingViewModel(val bookId: String, val database: ReadAlongDatabase, private val repository: TranscriptRepository, private val preferencesStore: ReaderPreferencesStore, private val epub: com.example.readalong.data.epub.GeneratedEpubRepository, private val processing: com.example.readalong.data.processing.ProcessingRepository, private val imported: com.example.readalong.data.epub.ImportedEpubRepository? = null, private val bookPreferencesStore: ReaderPreferencesStore? = null, initialReadOnly: Boolean = false) : ViewModel() {
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun clearError(expected: String) { if (mutableError.value == expected) mutableError.value = null }
    val preparationHidden = database.bookDao().observeMetadata("hide_readalong_bar:$bookId").map { it == "true" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun hidePreparation(hidden: Boolean) = action {
        database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("hide_readalong_bar:$bookId", hidden.toString()))
    }
    val processingJob = processing.observe(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val timeRemaining = processingJob.distinctUntilChangedBy { Triple(it?.completedUnits, it?.state, it?.workId) }
        .map { com.example.readalong.data.processing.TranscriptionEstimate.label(database, it) }.flowOn(Dispatchers.IO)
        .catch { emit("Estimating time remaining…") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Estimating time remaining…")
    val needsAudioTimingRepair = combine(processingJob, com.example.readalong.data.audio.Mp3Timing.correctedUris) { job, uris ->
        (job?.completedUnits ?: 0) > 0 && uris.isNotEmpty() &&
            database.bookDao().metadata(com.example.readalong.data.audio.Mp3Timing.versionKey(bookId)) != com.example.readalong.data.audio.Mp3Timing.VERSION &&
            database.mediaDao().audioFiles(bookId).any { it.uri in uris }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val preparationProgress = processingJob.map { it?.progress() }.flowOn(Dispatchers.IO).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val execution = processing.execution(bookId).catch { mutableError.value = "Preparation status is temporarily unavailable. Reopen this book to retry." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun startNow() = action { processing.transcribeNext(bookId) }
    fun restartSection() = action { processing.cancel(bookId); processing.transcribeNext(bookId) }
    fun rebuildTranscription() = action { processing.rebuild(bookId) }
    suspend fun epubFonts(editionId: String) = imported?.fontFamilies(editionId).orEmpty()
    val importedEdition = (imported?.observe(bookId) ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val mutableImportBusy = MutableStateFlow(false)
    val importBusy = mutableImportBusy.asStateFlow()
    private val mutableMissingEpub = MutableStateFlow(false)
    val missingEpub = mutableMissingEpub.asStateFlow()
    fun checkMissingEpub(isDemo: Boolean) = action {
        if (!isDemo && database.processingDao().find(bookId)?.state !in setOf(null, "WAITING_SELECTION") && database.importedEpubDao().find(bookId) == null &&
            database.bookDao().metadata("hide_missing_epub") != "true" &&
            database.bookDao().metadata("epub_prompt:$bookId") != "true") mutableMissingEpub.value = true
    }
    fun dismissMissingEpub(dontAskAgain: Boolean) = action {
        mutableMissingEpub.value = false
        database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("epub_prompt:$bookId", "true"))
        if (dontAskAgain) database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("hide_missing_epub", "true"))
    }
    fun startPreparationIfNeeded() = action { processing.ensureStarted(bookId) }
    fun addEpub(uri: android.net.Uri) = action {
        mutableImportBusy.value = true
        try { requireNotNull(imported).import(bookId, uri) }
        finally { mutableImportBusy.value = false }
    }
    fun chooseEdition(value: String, position: Long) = action { imported?.preference(bookId, value); openReader(position) }
    fun removeEpub(position: Long) = action { imported?.remove(bookId); openReader(position) }
    fun retryAlignment() = action { imported?.retry(bookId) }
    fun createWithSource(align: Boolean, uri: android.net.Uri?, onStarted: () -> Unit) = action {
        mutableImportBusy.value = true
        try {
            if (uri != null) requireNotNull(imported).import(bookId, uri)
            imported?.preference(bookId, if (align) "EPUB" else "GENERATED")
            repository.prepareDemo(bookId)
            processing.transcribeNext(bookId)
            onStarted()
        } finally { mutableImportBusy.value = false }
    }
    fun prepareReadable() = action { repository.prepareDemo(bookId); processing.transcribeNext(bookId) }
    fun allowBatteryForThirtyMinutes() = action { processing.allowBatteryForThirtyMinutes() }
    fun cancelPreparation() = action { processing.cancel(bookId) }
    val version = repository.version(bookId).catch { mutableError.value = "Readable text couldn't be loaded. Audio remains available." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val bookmarks = repository.bookmarks(bookId).catch { mutableError.value = "Bookmarks couldn't be loaded." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableReadOnly = MutableStateFlow(initialReadOnly)
    val readOnly = mutableReadOnly.asStateFlow()
    val bookPreferences = (bookPreferencesStore ?: preferencesStore).preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPreferences(theme = "LIGHT"))
    fun setReadOnly(enabled: Boolean, position: Long) = action {
        if (mutableReadOnly.value == enabled) return@action
        mutableReadOnly.value = enabled
        loadReader(position, resume = enabled, generated = true, retainPager = true)
    }
    suspend fun saveBookViewport(segment: TranscriptSegment, index: Int, character: Int) {
        database.bookDao().putMetadata(com.example.readalong.data.local.LibraryMetadataEntity("book_viewport:$bookId",
            org.json.JSONObject().put("edition", mutableStart.value?.editionId).put("index", index).put("id", segment.id).put("character", character).toString()))
        visible(segment, segment.anchorAtCharacter(character))
    }
    fun bookSettings(value: ReaderPreferences) = action { (bookPreferencesStore ?: preferencesStore).save(value.copy(theme = if (value.theme == "DARK") "DARK" else "LIGHT")) }
    val preferences = preferencesStore.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPreferences())
    private val mutableStart = MutableStateFlow<ReaderStart?>(null)
    val readerStart = mutableStart.asStateFlow()
    val readerPages = mutableStart.filterNotNull().distinctUntilChangedBy { it.pagerGeneration }.flatMapLatest { start ->
        val config = PagingConfig(pageSize = 40, initialLoadSize = 80, prefetchDistance = 10, maxSize = 240, enablePlaceholders = true)
        if (start.imported) Pager(config, initialKey = start.index) { database.importedEpubDao().paging(bookId, requireNotNull(start.editionId)) }
            .flow.map { data -> data.map { it.toReadingSegment() } }
        else Pager(config, initialKey = start.index) {
            if (start.generated) database.readingDao().generatedPaging(bookId) else database.readingDao().paging(bookId)
        }.flow.map { data -> data.map { it.toSegment() } }
    }.cachedIn(viewModelScope)
    private val mutableReadingAudio = MutableStateFlow(0L)
    val readingAudio = mutableReadingAudio.asStateFlow()
    private val mutableReadingHasAudio = MutableStateFlow(true)
    val readingHasAudio = mutableReadingHasAudio.asStateFlow()
    private val importedContents = MutableStateFlow(emptyList<ReadingChapter>())
    private val mutableChapters = MutableStateFlow(emptyList<TranscriptChapter>())
    val chapters = mutableChapters.asStateFlow()
    val contents = combine(chapters, processingJob, readerStart, importedContents) { savedChapters, job, start, epubChapters ->
        if (start?.imported == true) return@combine epubChapters
        val plan = job?.planJson?.let { com.example.readalong.data.processing.TranscriptionPlan.decode(it) }.orEmpty()
        if (plan.isEmpty()) savedChapters.map { ReadingChapter(it.chapterId, it.startMs, it.title, "Available", true) }
        else {
            val named = database.mediaDao().chapters(bookId).associate { it.id to it.title }
            val files = database.mediaDao().audioFiles(bookId).associate { it.id to it.name }
            plan.withIndex().groupBy { it.value.chapterId }.entries.mapIndexed { index, (id, entries) ->
                val done = entries.count { it.index < (job?.completedUnits ?: 0) }
                val status = when {
                    done == entries.size -> "Ready"
                    done > 0 -> "Partly ready · ${done * 100 / entries.size}%"
                    job?.state in listOf("QUEUED", "PROCESSING", "PARTIAL_READY", "GENERATING_EPUB") && entries.first().index == job?.completedUnits -> "Preparing"
                    job?.state == "CANCELLED" -> "Paused"
                    job?.state == "FAILED" -> "Waiting for retry"
                    job?.state == "WAITING_MODEL" -> "Needs speech model"
                    else -> "Queued"
                }
                ReadingChapter(id, entries.first().value.startMs, named[id] ?: files[id] ?: "Chapter ${index + 1}", status,
                    savedChapters.any { it.chapterId == id })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    init {
        viewModelScope.launch {
            importedEdition.filterNotNull().distinctUntilChangedBy { it.editionId to it.updatedAt }.collectLatest { edition ->
                if (mutableStart.value?.imported == true) {
                    try { loadImportedContents(edition) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableError.value = "Contents could not be refreshed. Reopen the book to retry." }
                }
            }
        }
    }
    private val mutableSearchResults = MutableStateFlow(emptyList<ReadingSearchResult>())
    val searchResults = mutableSearchResults.asStateFlow()
    private val mutableSearching = MutableStateFlow(false)
    val searching = mutableSearching.asStateFlow()
    private val mutableSearchError = MutableStateFlow<String?>(null)
    val searchError = mutableSearchError.asStateFlow()
    private var searchJob: Job? = null
    fun search(query: String) {
        searchJob?.cancel()
        mutableSearchResults.value = emptyList(); mutableSearchError.value = null; mutableSearching.value = false
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(350); mutableSearching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    val imported = database.importedEpubDao().find(bookId)
                    val useImported = imported != null && (mutableReadOnly.value || imported.useForReading())
                    val edition = if (useImported) imported?.editionId else database.readingDao().findVersion(bookId)?.activeEditionId
                    val scan = com.example.readalong.domain.reading.PassageSearch(query.take(500))
                    val hits = mutableListOf<ReadingSearchResult>()
                    var index = 0; var characters = 0
                    while (hits.size < 20) {
                        coroutineContext.ensureActive()
                        val page = if (useImported && edition != null) database.importedEpubDao().blockPage(bookId, edition, 200, index).map { it.toReadingSegment() }
                            else if (edition != null) database.readingDao().editionPage(bookId, edition, 200, index).map { it.toSegment() }
                            else database.readingDao().alignmentSegments(bookId, 200, index).map { it.toSegment() }
                        if (page.isEmpty()) break
                        for (segment in page) {
                            coroutineContext.ensureActive()
                            characters += segment.text.length
                            require(characters <= 16_000_000) { "Search reached the text processing limit. Try searching a shorter prepared edition." }
                            scan.feed(com.example.readalong.domain.reading.SearchPassage(index++, segment)).forEach { hit ->
                                if (hits.size < 20) hits += ReadingSearchResult(hit.passage.segment, hit.passage.index, hit.offset, hit.end, hit.preview, !useImported && edition != null, edition)
                            }
                            if (hits.size >= 20) break
                        }
                    }
                    return@withContext hits
                }
                mutableSearchResults.value = results
                if (results.isEmpty()) mutableSearchError.value = "No matches in the available text. Later audio may still be preparing."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableSearchError.value = failure.message ?: "Text search failed. Try again." }
            finally { mutableSearching.value = false }
        }
    }
    suspend fun selectSearch(result: ReadingSearchResult): Boolean {
        return try {
            val segment = if (result.segment.imported) {
                val edition = database.importedEpubDao().find(bookId)
                require(edition?.editionId == result.editionId) { "The reading edition changed. Search again." }
                requireNotNull(database.importedEpubDao().block(bookId, result.editionId!!, result.index)).toReadingSegment()
            } else {
                if (result.generated) require(database.readingDao().findVersion(bookId)?.activeEditionId == result.editionId) { "The reading edition changed. Search again." }
                if (result.generated) result.segment else {
                    val current = database.readingDao().alignmentSegments(bookId, 1, result.index).singleOrNull()?.toSegment()
                    require(current?.id == result.segment.id && current.text == result.segment.text) { "The recognized text changed. Search again." }
                    current
                }
            }
            val position = sentenceTarget(segment, result.offset) ?: -1L
            ++openRequest
            if (position < 0) visible(segment, position)
            mutableStart.value = ReaderStart(result.index, ++readerGeneration, result.generated, segment.imported, result.editionId, result.offset, mutableReadOnly.value)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableSearchError.value = failure.message ?: "Couldn't open this result. Search again."; false }
    }
    private var transientNotice: Job? = null
    private fun notice(message: String) {
        transientNotice?.cancel(); mutableError.value = message
        transientNotice = viewModelScope.launch { delay(5_000); clearError(message) }
    }
    private var progressSave: Job? = null
    // Preview formatting can warm without choosing a reader position before the user seeks.
    fun warmPreview() = action {
        repository.prepareDemo(bookId)
        if (database.readingDao().count(bookId) > 0) epub.refreshReading(bookId)
    }
    private var readerGeneration = 0
    private var openRequest = 0
    private var preloading = false
    fun preloadReader(positionMs: Long) {
        if (preloading || (mutableStart.value != null && kotlin.math.abs(positionMs - mutableReadingAudio.value) < 1000)) return
        preloading = true
        mutableStart.value = null // Keep the retained preview visible until this requested position is ready.
        action { try { loadReader(positionMs, false, true) } finally { preloading = false } }
    }
    fun openReader(positionMs: Long, resume: Boolean = false, generated: Boolean = true) = action { loadReader(positionMs, resume, generated) }
    private suspend fun loadReader(positionMs: Long, resume: Boolean, generated: Boolean, retainPager: Boolean = false) {
        val bookMode = mutableReadOnly.value
        val request = ++openRequest
        repository.prepareDemo(bookId)
        val dao = database.readingDao()
        // Catch up derived text on entry; normal playback never repeats recognition.
        val previous = dao.findVersion(bookId)
        try { imported?.upgradeContents(bookId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
        val myEpub = database.importedEpubDao().find(bookId)
        val useImported = generated && myEpub != null && (bookMode || myEpub.useForReading())
        if (generated && dao.count(bookId) > 0) {
            try { epub.refreshReading(bookId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (previous?.activeEditionId == null) throw failure
                mutableError.value = "Showing the saved edition. New text will update automatically when preparation continues."
            }
        }
        val useEdition = !useImported && generated && dao.findVersion(bookId)?.activeEditionId != null
        val saved = if (resume) dao.progress(bookId) else null
        val target = saved?.audioMs ?: positionMs
        mutableReadingAudio.value = target
        mutableReadingHasAudio.value = true
        if (useImported && myEpub != null) {
            loadImportedContents(myEpub)
        }
        val headings = database.mediaDao().chapters(bookId).associate { it.id to it.title }
        val files = database.mediaDao().audioFiles(bookId).associate { it.id to it.name }
        mutableChapters.value = (if (useEdition) dao.generatedChapters(bookId) else dao.chapters(bookId)).mapIndexed { index, chapter ->
            chapter.copy(title = headings[chapter.chapterId] ?: files[chapter.chapterId] ?: "Chapter ${index + 1}")
        }
        var index = if (useImported && saved?.epubEditionId == myEpub?.editionId && saved?.epubOrdinal != null) saved.epubOrdinal
            else indexAt(target, useEdition, useImported)
        if (request != openRequest) return
        if (useImported && myEpub != null) mutableReadingHasAudio.value =
            !(saved?.epubEditionId == myEpub.editionId && saved?.epubOrdinal == index && saved.segmentId.startsWith("epub:")) &&
                database.importedEpubDao().block(bookId, myEpub.editionId, index)?.startMs?.let { it >= 0 } == true
        val editionId = if (useImported) myEpub?.editionId else if (useEdition) dao.findVersion(bookId)?.activeEditionId else null
        var character = 0
        if (bookMode) {
            val savedViewport = runCatching { org.json.JSONObject(database.bookDao().metadata("book_viewport:$bookId") ?: "{}") }.getOrNull()
            if (savedViewport != null && savedViewport.optString("edition") == editionId && savedViewport.has("index")) {
                val candidate = savedViewport.getInt("index")
                val matches = if (useImported) database.importedEpubDao().block(bookId, requireNotNull(editionId), candidate)?.toReadingSegment()?.id == savedViewport.optString("id")
                    else if (useEdition) dao.editionPage(bookId, requireNotNull(editionId), 1, candidate).firstOrNull()?.id == savedViewport.optString("id")
                    else dao.sourceSegments(bookId, listOf(savedViewport.optString("id"))).isNotEmpty()
                if (matches) { index = candidate; character = savedViewport.optInt("character", 0).coerceAtLeast(0) }
            }
        }
        val generation = ++readerGeneration
        val retained = mutableStart.value?.takeIf { retainPager && it.generated == useEdition && it.imported == useImported && it.editionId == editionId }
        mutableStart.value = ReaderStart(index, generation, useEdition, useImported, editionId, character, bookMode, retained?.pagerGeneration ?: generation)
    }
    private suspend fun loadImportedContents(edition: com.example.readalong.data.local.ImportedEpubEntity) {
        importedContents.value = withContext(Dispatchers.IO) {
                val blocks = database.importedEpubDao().blocks(bookId, edition.editionId)
                val nav = runCatching {
                    val rows = org.json.JSONArray(database.bookDao().metadata("epub_contents:${edition.editionId}") ?: "[]")
                    (0 until rows.length()).map { rows.getJSONObject(it).let { row -> row.getInt("ordinal") to row.getString("title") } }
                }.getOrDefault(emptyList())
                val entries = if (nav.isNotEmpty()) nav else blocks.groupBy { it.spine }.values.map { section ->
                    section.first().ordinal to (section.filter { it.kind.startsWith("H") }.take(3).joinToString(" · ") { it.text }.ifBlank { section.first().text.take(100) })
                }
                val ordered = entries.distinctBy { it.first }.sortedBy { it.first }
                ordered.mapIndexed { index, (ordinal, title) ->
                    val end = ordered.getOrNull(index + 1)?.first ?: Int.MAX_VALUE
                    val section = blocks.filter { it.ordinal in ordinal until end }
                    val timed = section.filter { it.startMs >= 0 }
                    ReadingChapter("epub:$ordinal", timed.minOfOrNull { it.startMs } ?: -1,
                        title, if (timed.isEmpty()) "Text ready · audio not matched yet" else "Audio matched", true, ordinal)
                }
            }
    }
    fun openEpubSection(index: Int) = action {
        val start = mutableStart.value ?: return@action
        val edition = database.importedEpubDao().find(bookId) ?: return@action
        val block = database.importedEpubDao().block(bookId, edition.editionId, index) ?: return@action
        visible(block.toReadingSegment())
        mutableStart.value = start.copy(index = index, generation = ++readerGeneration, characterOffset = 0)
    }
    suspend fun indexAt(position: Long, generated: Boolean = mutableStart.value?.generated == true, imported: Boolean = mutableStart.value?.imported == true): Int {
        val dao = database.readingDao()
        return if (imported) database.importedEpubDao().let { it.indexAt(bookId, position) ?: it.indexBefore(bookId, position) ?: 0 }
        else if (generated) dao.generatedCountBefore(bookId, position)
        else dao.countBefore(bookId, dao.at(bookId, position)?.startMs ?: position)
    }
    private val mutableManualSync = MutableStateFlow<com.example.readalong.data.epub.ManualSyncDraft?>(null)
    val manualSync = mutableManualSync.asStateFlow()
    private val mutableSyncBusy = MutableStateFlow(false)
    val syncBusy = mutableSyncBusy.asStateFlow()
    fun editSync(segment: TranscriptSegment, position: Long) = action {
        require(segment.imported)
        val edition = segment.id.removePrefix("epub:").substringBefore(':')
        val ordinal = segment.id.substringAfterLast(':').toInt()
        mutableManualSync.value = requireNotNull(imported).manualDraft(bookId, edition, ordinal, position)
    }
    fun dismissSync() { if (!mutableSyncBusy.value) mutableManualSync.value = null }
    fun saveSync(start: Long, end: Long, reset: Boolean) = action {
        val draft = requireNotNull(mutableManualSync.value)
        mutableSyncBusy.value = true
        try {
            requireNotNull(imported).saveManual(draft, start, end, reset)
            mutableManualSync.value = null
            openReader(start)
        } finally { mutableSyncBusy.value = false }
    }
    /** Resolve taps from persisted canonical words, including passages outside the current audio window. */
    suspend fun sentenceTarget(paragraph: TranscriptSegment, offset: Int): Long? = try { withContext(Dispatchers.IO) {
        suspend fun target(segment: TranscriptSegment, at: Int): Long? {
            val ids = segment.anchors.map { it.id }.ifEmpty { listOf(segment.id) }
            val sources = database.readingDao().sourceSegments(bookId, ids).map { it.toSegment() }.associateBy { it.id }
            val cues = sentenceCues(segment, sources)
            val selected = sentenceAt(cues, at) ?: return null
            return cues.lastOrNull { it.start <= selected.start && it.audioStartMs != null }?.let {
                val preceding = database.readingDao().precedingForSeek(bookId, it.audioStartMs!!)?.toSegment()
                com.example.readalong.domain.reading.sentenceSeekStart(it, sources.values + listOfNotNull(preceding))
            }
        }
        var located = paragraph
        var result = target(paragraph, offset)
        if (result == null && paragraph.imported) {
            val edition = paragraph.id.removePrefix("epub:").substringBefore(':')
            var before = paragraph.id.substringAfterLast(':').toIntOrNull() ?: 0
            while (before > 0 && result == null) {
                coroutineContext.ensureActive()
                val from = (before - 40).coerceAtLeast(0)
                val page = database.importedEpubDao().blockPage(bookId, edition, before - from, from)
                for (block in page.asReversed()) {
                    val segment = block.toReadingSegment()
                    if (segment.startMs < 0) continue
                    result = target(segment, segment.text.lastIndex)
                    if (result != null) { located = segment; break }
                }
                before = from
            }
        }
        if (result != null) withContext(Dispatchers.Main.immediate) { visible(located, result!!) }
        else withContext(Dispatchers.Main.immediate) { notice("No earlier sentence has audio timing yet. Keep listening while transcription prepares it.") }
        result
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { withContext(Dispatchers.Main.immediate) { notice("This saved passage could not be loaded. Reopen the book to retry; audio and EPUB are kept.") }; null }
    /** Bounded pages also allow sentence navigation across unaligned paragraphs and chapters. */
    suspend fun adjacentSentence(position: Long, direction: Int): Long? = try { withContext(Dispatchers.IO) {
        require(direction == -1 || direction == 1)
        val start = mutableStart.value ?: return@withContext null
        var index = indexAt(position)
        var skipCurrent = direction < 0
        while (index >= 0) {
            coroutineContext.ensureActive()
            val from = if (direction < 0) (index - 39).coerceAtLeast(0) else index
            val count = if (direction < 0) index - from + 1 else 40
            val page = when {
                start.imported -> database.importedEpubDao().blockPage(bookId, start.editionId!!, count, from).map { it.toReadingSegment() }
                start.generated -> database.readingDao().editionPage(bookId, start.editionId!!, count, from).map { it.toSegment() }
                else -> database.readingDao().segments(bookId, count, from).map { it.toSegment() }
            }
            if (page.isEmpty()) break
            val ids = page.flatMap { p -> p.anchors.map { it.id }.ifEmpty { listOf(p.id) } }.distinct()
            val sources = ids.chunked(400).flatMap { database.readingDao().sourceSegments(bookId, it) }.map { it.toSegment() }.associateBy { it.id }
            val positions = page.flatMap { sentenceCues(it, sources) }.mapNotNull { it.audioStartMs }.distinct().sorted()
            if (direction > 0) positions.firstOrNull { it > position + 200 }?.let { return@withContext it }
            else for (time in positions.asReversed().filter { it <= position }) {
                if (skipCurrent) skipCurrent = false else return@withContext time
            }
            index = if (direction < 0) from - 1 else from + page.size
        }
        mutableError.value = if (direction < 0) "No earlier timed sentence is available." else "No later timed sentence is available yet."
        null
    } } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { withContext(Dispatchers.Main.immediate) { notice("Sentence timing could not be loaded. Reopen this book to retry.") }; null }
    private val mutableCorrection = MutableStateFlow<CorrectionDraft?>(null)
    val correction = mutableCorrection.asStateFlow()
    fun editPassage(segmentId: String) = action {
        val raw = requireNotNull(database.readingDao().segment(segmentId)) { "This saved passage no longer matches the current transcript." }
        check(raw.bookId == bookId)
        val correction = database.readingDao().correction(bookId, segmentId)?.takeIf { it.originalText == raw.text }
        mutableCorrection.value = CorrectionDraft(segmentId, raw.text, correction?.replacement ?: raw.text, raw.startMs)
    }
    fun dismissCorrection() { mutableCorrection.value = null }
    fun saveCorrection(text: String, restore: Boolean = false) = action {
        val draft = requireNotNull(mutableCorrection.value)
        require(text.isNotBlank() && text.length <= 8000)
        database.withTransaction {
            val dao = database.readingDao()
            check(dao.segment(draft.id)?.text == draft.original) { "Recognition changed; reopen the passage before editing." }
            if (restore || text == draft.original) dao.deleteCorrection(bookId, draft.id)
            else dao.putCorrection(TranscriptCorrectionEntity(bookId, draft.id, draft.original, text.trim(), System.currentTimeMillis()))
            dao.findVersion(bookId)?.let { dao.putVersion(it.copy(transcriptRevision = it.transcriptRevision + 1)) }
        }
        mutableCorrection.value = null
        epub.generate(bookId)
        try { imported?.requestAlignment(bookId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.w("ReadAlong", "The correction is saved; EPUB matching will retry on app launch.") }
        openReader(draft.audioMs)
    }
    fun importTranscript(context: android.content.Context, uri: android.net.Uri) = action {
        if (mutableImportBusy.value) return@action
        mutableImportBusy.value = true
        try {
            com.example.readalong.data.transcription.DesktopTranscriptImporter(context, database, processing, epub).import(bookId, uri)
            imported?.requestAlignment(bookId)
            openReader(0)
        } finally { mutableImportBusy.value = false }
    }
    fun export(uri: android.net.Uri) = action { epub.export(bookId, uri) }
    fun visible(segment: TranscriptSegment, position: Long = segment.startMs) {
        mutableReadingHasAudio.value = position >= 0 && segment.endMs > position
        if (mutableReadingHasAudio.value) mutableReadingAudio.value = position
        val audioPosition = mutableReadingAudio.value
        val editionId = if (segment.imported) segment.id.removePrefix("epub:").substringBefore(':') else null
        progressSave?.cancel()
        progressSave = viewModelScope.launch {
            delay(300)
            try { database.readingDao().putProgress(ReadingProgressEntity(bookId, segment.anchors.firstOrNull { position >= it.startMs && position < it.endMs }?.id ?: segment.id, audioPosition, System.currentTimeMillis(),
                editionId, if (segment.imported) segment.id.substringAfterLast(':').toIntOrNull() else null)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = "Reading position couldn't be saved." }
        }
    }
    fun settings(value: ReaderPreferences) = action { preferencesStore.save(value) }
    private val center = MutableStateFlow(0L)
    val passages = center.flatMapLatest { position ->
        combine(repository.window(bookId, position), database.importedEpubDao().window(bookId, position), database.readingDao().generatedWindow(bookId, position), importedEdition) { raw, epubBlocks, generated, edition ->
            val shown = if (edition?.useForReading() == true) epubBlocks.map { it.toReadingSegment() } else generated.map { it.toSegment() }
            // A publisher paragraph can span many audio windows. Its opening words must
            // remain available even when the current raw window has moved beyond them.
            val missing = shown.flatMap { it.anchors.map { anchor -> anchor.id } }.distinct().filter { id -> raw.none { it.id == id } }
            val sources = withContext(Dispatchers.IO) {
                missing.chunked(400).flatMap { database.readingDao().sourceSegments(bookId, it) }.map { it.toSegment() }
            }
            shown + (raw + sources).distinctBy { it.id }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { mutableError.value = "Text couldn't be loaded. Audio remains available." }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TranscriptSegment>())
    fun follow(positionMs: Long) { center.value = positionMs / 12_000 * 12_000 }
    fun bookmark(positionMs: Long, label: String? = null) = action { repository.addBookmark(bookId, positionMs, label) }
    fun deleteBookmark(id: String) = action { repository.removeBookmark(id) }
    private fun action(work: suspend () -> Unit) {
        viewModelScope.launch {
            mutableError.value = null
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = failure.message ?: "Couldn't save this change. Please try again." }
        }
    }
    companion object {
        fun factory(bookId: String, database: ReadAlongDatabase, repository: TranscriptRepository, preferences: ReaderPreferencesStore, epub: com.example.readalong.data.epub.GeneratedEpubRepository, processing: com.example.readalong.data.processing.ProcessingRepository, imported: com.example.readalong.data.epub.ImportedEpubRepository? = null, bookPreferences: ReaderPreferencesStore? = null, initialReadOnly: Boolean = false): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingViewModel(bookId, database, repository, preferences, epub, processing, imported, bookPreferences, initialReadOnly) as T
        }
    }
}

data class ReaderStart(val index: Int, val generation: Int, val generated: Boolean = false, val imported: Boolean = false, val editionId: String? = null, val characterOffset: Int = 0, val bookMode: Boolean = false, val pagerGeneration: Int = generation)

data class CorrectionDraft(val id: String, val original: String, val text: String, val audioMs: Long)

data class ReadingChapter(val id: String, val startMs: Long, val title: String, val status: String, val readable: Boolean, val readerIndex: Int? = null)

data class ReadingSearchResult(val segment: TranscriptSegment, val index: Int, val offset: Int, val end: Int,
    val preview: String, val generated: Boolean, val editionId: String?) {
    val audioMs: Long? get() = segment.anchors.firstOrNull { offset in it.start until it.end }?.startMs
        ?: segment.startMs.takeIf { !segment.imported && it >= 0 }
}

private fun TranscriptSegment.anchorAtCharacter(character: Int): Long = anchors.firstOrNull { character in it.start until it.end }?.startMs ?: startMs
