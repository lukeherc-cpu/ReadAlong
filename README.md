# ReadAlong

Audiobook player for Android that highlights the text as the narrator reads. Works offline, with your own files.
Turn any DRM-free audiobook into readable text on your phone, or sync it to an EPUB you already own. No account, no uploads.

**[Download ReadAlong 0.35.4 Preview](https://github.com/lukeherc-cpu/ReadAlong/releases/download/v0.35.4-preview/ReadAlong-0.35.4-preview.apk)** · [View the release](https://github.com/lukeherc-cpu/ReadAlong/releases/tag/v0.35.4-preview)

ReadAlong works with your own audiobook files. It does not include a book catalogue, sell books, or require an account to listen and read.

## Contents

- [Download and install](#download-and-install)
- [Your first book](#your-first-book)
- [Build your library](#build-your-library)
- [Listen](#listen)
- [Read along with the narration](#read-along-with-the-narration)
- [Read without audio](#read-without-audio)
- [Use your own EPUB](#use-your-own-epub)
- [Create text on your device](#create-text-on-your-device)
- [Manage the transcription queue](#manage-the-transcription-queue)
- [Make it comfortable to read](#make-it-comfortable-to-read)
- [Your files and privacy](#your-files-and-privacy)
- [Common questions](#common-questions)
- [Source code and license](#source-code-and-license)
- [Support ReadAlong](#support-readalong)

## Download and install

The current download is **ReadAlong 0.35.4 Preview** for **Android 8.0 or later on an ARM64 device**. It runs on compatible phones and tablets.

1. [Download the APK](https://github.com/lukeherc-cpu/ReadAlong/releases/download/v0.35.4-preview/ReadAlong-0.35.4-preview.apk) on your Android device.
2. Open it and allow installation from your browser or file manager if Android asks.
3. Open ReadAlong and choose an audiobook folder. You can choose **Later** and add books from the library instead.

ReadAlong downloads its small offline speech model in the background. You can listen to your books while that download finishes. Once the model is installed, listening, transcription and reading work without an internet connection.

When installing a later version, install it over the existing app to keep your library, reading positions and prepared text.

## Your first book

Tap **+** in the library to add an audiobook. Choose a single audio file, select several files that belong to one book, or choose the book's folder.

Open the book to start listening. To read along, choose **Create ReadAlong**. You can use the generated transcript on its own, or pair the recording with a matching EPUB. If the book's folder already contains an EPUB, ReadAlong picks it up for you.

Tap the cover in the player to turn it over and see the text preview. Expand that preview for the full-screen reader. The switch at the top of the reader lets you move between listening with the text and reading without audio.

## Build your library

### One file, a multipart book, or a collection

A book can be one long recording or a folder of chapter files. ReadAlong groups the parts of a book together and orders them using disc and track information where available, with natural filename ordering for the remaining files. Nested disc folders can belong to the same book.

For a collection, open **Library folders** from the bookshelf icon and add the folder containing your books. ReadAlong scans the saved folders when you return to the library, so newly added books can appear without selecting every file again. The folder screen also lets you rescan a folder or remove it from the library.

Supported file extensions include **MP3, M4A, M4B, AAC, OGG, Opus, FLAC, WAV and MP4 audio**. Playback depends on the audio codec in the file and your device's decoder support. Use local, DRM-free files that Android can read.

### Find a book

The library separates books by listening progress, including books you have not started, books in progress and finished books. Search by title or author, choose a sort order, and switch between cover and list views.

Cover view can show just the artwork or include the title and author underneath. List view keeps the title and author visible. A book without artwork has its title on the cover placeholder.

### Edit a book

Hold a book cover to open its menu. From there you can:

- Change the title or author.
- Choose a cover image from your files.
- Search for a cover online, then crop or zoom it before saving.
- Mark the book as not started or finished.
- Delete the book from ReadAlong.

Deleting a book removes its saved app data, including prepared text and listening positions. It leaves the original audio and EPUB files in your folders. Removing a saved library folder asks for confirmation before removing its books and their app data.

## Listen

### Playback controls

The player shows the cover, current chapter, playback position and time information. You can drag the progress slider to move within a chapter, open the chapter list to choose another section, and change playback speed.

Rewind and forward buttons let you move by a set number of seconds. Their intervals are adjustable in Settings, with separate values for the main player and the full-screen reader. The reader also has previous- and next-sentence controls for shorter moves through the text.

### Keep listening outside the app

Audio continues when you leave ReadAlong or turn off the screen. The media notification provides playback controls, and pausing leaves a way to play again without reopening the library.

The mini-player at the bottom of the library gives you quick access to the current book. Tap it to open the full player. Swipe it left to dismiss it and stop playback; your listening position is kept.

### Bookmarks and sleep timer

Save a bookmark at the current audio position and give it a name. Open the bookmark list to jump back to that passage or remove a bookmark you no longer need.

The sleep timer can stop playback after a selected number of minutes or at the end of the current chapter.

## Read along with the narration

ReadAlong highlights the current sentence as the audiobook plays and moves the text with the narration. You can use the compact preview behind the cover or expand it into a full-screen reading view.

### Tap the words to hear them

In the full-screen listening view, a tap reveals the controls without pausing the book. With the controls showing, tap a timed sentence to start listening from that passage. The controls then disappear so you can follow the text. When audio is paused, tapping a timed sentence starts playback there.

Controls hide after a short period without another tap. Scrolling lets you browse ahead or revisit an earlier passage. **Return to current position** brings you back to the narration when you are ready.

### Search and chapters

Search the reading text for a word or phrase, open a result to see it in context, and choose when to start listening. The contents button gives you another route through the book's chapters.

Text becomes available as transcription progresses. You can read completed sections while the rest of the audiobook is being prepared.

### Correct the generated text

Speech recognition can mishear names or unfamiliar words. Hold a passage in the generated transcript to correct it. The original recognition is kept, and **Restore original recognition** lets you undo the correction. Saved corrections also appear in the generated EPUB export.

## Read without audio

Choose the **book** side of the switch at the top of the full-screen reader to read without sentence highlighting or playback controls. Choose the **book with headphones** to return to listening and reading together.

The two modes share your current passage and keep separate appearance preferences. Switching keeps the same text in view, even with different fonts or text sizes. Read-only mode uses a white page with dark text in light mode and a black page with light text in dark mode. Returning to ReadAlong moves the audio to the displayed passage when its timing is available.

You can also choose **Start in read only** in Settings if you usually want a book to open as a reading page. With an imported EPUB, its text can be read before audio matching is complete.

## Use your own EPUB

A matching EPUB lets you follow the published text instead of the words produced by speech recognition. ReadAlong keeps its paragraphs, emphasis and supported embedded fonts while matching passages to the audiobook.

### Add an EPUB

Put the EPUB in the audiobook's folder before importing the book, or open the player's **More** menu and choose **Add my EPUB**. You can replace that EPUB later if you find a different edition.

The original EPUB is the default reading edition when one is available. You can switch to **ReadAlong generated** if you prefer the transcript. Adding or replacing an EPUB uses the transcription already saved for the book; you do not need to transcribe the audio again.

The first matching pass has a progress indicator. Matching continues as more of the audiobook is transcribed, and the reader can show the EPUB's full text while timing is still being prepared.

### Match an edition to the recording

The closest match is an EPUB of the same edition as the audiobook. Introductions, abridgements, skipped passages and differences between editions can leave some text without a confident audio match.

If you need to place a passage yourself, pause at the corresponding point in the audio, hold its EPUB paragraph in the listening view, and choose **Set sync point**. You can review the suggested interval and save it. **Reset to automatic sync** removes that manual point.

### Export a reading copy

The player's menu includes **Export generated EPUB**. This saves a reading copy of the text prepared by ReadAlong, including saved transcript corrections, to a location you choose.

## Create text on your device

ReadAlong uses **Cactus Whistle** for offline speech recognition. The model download is approximately **17 MB**; the audiobook itself is processed on your device. There is no transcription account or per-book transcription charge.

Start preparation with **Create ReadAlong**. You can keep listening while it works, and completed text is saved along the way. A paused job can continue from its saved progress.

The preparation bar in the player shows progress and offers pause and resume controls. You can hide the bar and find its controls again under **More**. The transcription notification shows the current section, chapter progress and whole-book progress, with controls for pausing or resuming outside the app.

### Background and charging options

For transcription while using other apps or with the screen off, open **Settings → Transcription → Background transcription and battery** and allow background processing. Android's battery settings and the device's temperature can still affect processing speed.

**Only transcribe while charging** lets you leave preparation for when the device is plugged in. An unplugged job waits for power and resumes when it is connected. When you want to make an exception, **Use battery for 30 min** temporarily allows processing without changing your charging preference.

The installed speech model can be managed under **Settings → Other → Offline speech models**.

## Manage the transcription queue

Tap the **ReadAlong logo** on the library page to open the queue. It is the list of books waiting for readable text, separate from the book you are listening to.

ReadAlong transcribes **one book at a time**. When that book finishes, the next waiting book starts automatically. The library logo can animate while transcription is active; you can turn that animation off in Settings.

In the queue you can:

- Use **+** to add a book from the library, or **Add all** to add books in the Not transcribed section.
- Press a book's play button to move it to the front and begin its transcription. The previous book keeps its saved progress while it waits.
- Pause the active book and resume it later.
- Drag the four-dot handle to reorder waiting books.
- Use the trash button to move a book to **Not transcribed** without deleting its audio or saved transcription progress.
- Open a book's details to see more information about its preparation.

Adding a collection to the library does not automatically transcribe every book. You choose which books to prepare.

## Make it comfortable to read

### Library and player

Choose a light or dark theme and a basic color. The palette carries through the library icons and selection colors, with matching launcher icon variants. Changes to the icon and opening colors can require restarting the app; ReadAlong asks before applying them.

The mini-player can use the current book's colors. The full player offers either a blurred-cover background or a solid color drawn from the cover.

Choose whether a book opens to its cover, a text preview, full-screen ReadAlong or reading-only mode. You can also hide title and author labels in cover view.

### Text and fonts

The reader offers font size, line spacing and margin controls, along with light, dark and sepia appearances for listening and reading. Reading-only mode has its own light and dark settings.

Use a bundled font, keep an EPUB's supported embedded fonts, or import a **TTF, OTF or TTC** font file. Imported fonts are saved in the app for future use. Available families include Jakarta, Lora, Literata, Merriweather, Libre Baskerville, EB Garamond, Source Serif 4, Open Sans, Lato, Montserrat and Bitter, alongside system serif, sans serif and monospace choices.

The app's interface font can be set separately from the reading font. The compact text preview can match the reader or use a different font of its own.

## Your files and privacy

Your library is built from files you select through Android's file and folder picker. ReadAlong keeps listening progress, reading positions, bookmarks, imported fonts, prepared text and EPUB matching data on the device.

Audiobook audio and EPUB text are not uploaded for transcription or matching. An internet connection is used to download the speech model and when you choose to search for cover artwork online. Playback and reading do not depend on a cloud library.

Keep your source files in a location that remains accessible to the app. Moving files, removing a storage device or withdrawing a folder's access can make a book unavailable until you restore access or add its folder again.

## Common questions

### Do I need an EPUB?

No. ReadAlong can create text from the audiobook alone. An EPUB is useful when you want to read the original edition's wording and formatting.

### Can I listen before the text is ready?

Yes. Adding a book makes its audio available for playback. Creating readable text is a separate step, and completed sections can be read while later ones are still being transcribed.

### Does the whole book have to be prepared before I can read?

No. Generated text appears as sections finish. With an imported EPUB, you can also read the book's text while its audio timing is being matched.

### What happens when I pause transcription?

Completed work remains saved. Resume from the player, queue or transcription notification to continue preparation. Charging-only jobs wait for a power connection rather than discarding their progress.

### Will deleting a book erase my audio files?

No. Deleting a book removes its data from ReadAlong. Your original audio and EPUB files stay in their folders.

### Does ReadAlong work with books from an audiobook subscription service?

ReadAlong opens local, DRM-free files. It does not sign in to audiobook services or remove copy protection from their downloads.

## Source code and license

The application source is available in this repository under **[GNU GPLv3 with the Needle Runtime Linking Exception](LICENSE)**. Distributed modifications to ReadAlong remain subject to GPLv3's source requirements. Third-party libraries and fonts retain their own licenses; see [NOTICE.md](NOTICE.md) and **About ReadAlong → Software licenses** in the app.

<details>
<summary>Build from source</summary>

Open the project in Android Studio with Android SDK 37, NDK 28.2.13676358 and CMake 3.22.1 installed. Use a compatible JDK, such as the one bundled with Android Studio.

```sh
./gradlew :app:assembleGithubDebug
./gradlew :app:assembleGithubRelease
```

To run the local checks:

```sh
./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug
./gradlew :app:assembleGithubDebugAndroidTest
```

Release APKs need signing before installation. Keep signing keys outside source control. The pinned ARM64 native speech runtime and its license are included under `third_party/needle`; speech-model weights are downloaded separately by the app.

</details>

## Support ReadAlong

The GitHub download is free. If you would like to support development, you can leave an optional tip at **[ko-fi.com/readalong](https://ko-fi.com/readalong)** or open the tip jar in Settings. Tips do not unlock features. The Settings checkbox lets you move the tip jar to the bottom of Other if you prefer.

A **Google Play edition is planned at $0.99**, with the same reading and playback features and no tip jar. It is not available on Google Play yet; the APK linked above is the current download.
