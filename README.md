# Private Character Chat 5.0 — Local Circle

**Delivery status: complete source changes; no APK was produced in this session.**
The actual Android build attempt exited before compilation because Gradle and the
Android SDK were absent. Download attempts failed DNS resolution. See
`build-evidence/APK_BUILD_ATTEMPT.txt`. Host tests are not an Android build.

## The new app layout

Local Circle is an Instagram-inspired **private fictional social feed**, not an
Instagram client and not a connection to real people. It has Feed, Chats, Cast,
Scenes and You tabs. The original private character chat is retained under Chats.
There are no fabricated starter conversations or prefilled social posts.

### Feed and comments

Use the header + button to create a text or image post. Select yourself or one of
your fictional characters as its author. Add a caption and an optional image
content description. Posts support likes, saved posts, editing and deletion.
A character's own gallery can provide its post image; the app does not generate
new images.

Open Comments under a post. You can reply directly to a particular comment, edit
your text, copy it, like it, or delete a comment and all replies underneath it.
The composer remains associated with the selected parent comment. Deleting a
branch removes its descendants, not another unrelated conversation.

**Reply as…** lets you select up to three enabled fictional characters. Their
replies are generated sequentially with the loaded local model. A reply uses
that character's profile, voice, role, relationship, mood and gallery. Optional
automatic owner replies trigger one response to your comment. This is deliberately
not an autonomous endless bot-to-bot loop.

Social reply context contains only that post and its public comments. It searches
the newest 500 comments in the thread and keeps the exact parent chain, even when
a selected reply target is older. A character cannot read your private DM transcript
through this code path. The original private-chat memory system stays separate.

### Your identity and the cast

The You tab stores your name, adult age, gender, pronouns, role and bio. Gender
choices include unspecified, man, woman, nonbinary and custom. Your explicit
identity reaches both public reply prompts and private chat prompts. Neither gender
nor a photo is treated as evidence of anatomy, preferences or consent.

Cast lets you add and configure fictional adults individually. Edit the name,
handle, role, relationship stage, personality, character traits, motivations,
reply instructions, boundaries, voice and gallery. Suggested user roles include
friend, coworker, neighbor, partner, creator, photographer, traveler, mentor,
adult student, adult teacher and custom. Roles can also be written freely.

Moods are independent per cast member: Off, Manual or Automatic, with friendliness,
love/affection, trust, annoyance, tiredness and playfulness. In the feed, automatic
state is replayed from that character's surviving source-linked user interactions.
Deleting a branch recalculates the affected state. A manual mood does not evolve
behind your back. These are simulated variables, not actual feelings or a trained
model brain.

The original character is imported from your existing profile and current mood.
Editing that main profile in Cast updates the original private-chat profile while
preserving its selected GGUF and runtime settings. Added cast members currently
participate in social comments; this does not add separate DM inboxes for every
cast member.

### Scenes and reply directions

Scenes can be global to the fictional world or specific to a post. A post-specific
field overrides the matching global field without deleting the remaining global
scene. Removing a post override returns to the global scene, when one is active.

Examples (fictional adults):

```text
*scene: old friends meeting at a station cafe*
*location: the station cafe*
*cast: Elena, a friend; Alex, a neighbor*
*my role: a friend visiting from another city*
*goal: find out why I arrived late*
*reply: ask one short, curious question without sounding angry*
```

Persistent scene facts remain until edited or cleared. A `reply:` direction is
consumed only after a successfully posted model reply. An error or discarded draft
does not silently consume it. Edit the scene through Scenes or the post's scene
controls. The existing *asterisk* director system in private chat remains.

Public comments always remain written social comments, even when the fictional
background scene describes a physical location. They should not become standalone
performed actions such as “winks at you”. Private Roleplay Studio still offers
Texting, Roleplay and Narrative formats for intentional narrated roleplay.

### Nine phone-writing styles

Clean, Natural, Casual, Minimal, Warm, Dry humor, Playful, Formal and Expressive.
They are instruction-based styles, with mild optional punctuation relaxation.
There is no random character scrambling. Names, dates, numbers, negation and
scene directives are protected from that mechanical formatting step. A model may
still misunderstand an instruction; output quality depends on the local model.

### Photos: when to send, and when not to

Each character has Never, Request only or Contextual sending. Automatic attachments
in public replies are separately opt-in and disabled by default. Galleries contain
existing local image files, with caption, description and tags.

An explicit request, affirmative acceptance, relevant matching photo, repeat
avoidance and cooldown are considered. A refusal, “maybe later”, clarification
question or your instruction not to send a picture prevents an attachment. An
internal photo marker does not override those rules. Specific unmatched requests
no longer get an unrelated random gallery photo.

If a model promises an unavailable/disallowed attachment, its draft is not posted;
a retryable error is shown instead. No generic “I'm here” filler is fabricated.
Manual post creation from either side remains available independent of automatic
reply permissions.

This is still a text-only GGUF pipeline. The model receives image descriptions,
not pixels. It cannot verify that your description or tags match the actual image.
No new pictures are generated, and no online image service is used.

## Performance and background work

Feed views are paginated (30 posts initially; comments in batches of 80). Images
are sampled and thumbnail-cached; imports are bounded to 2048 pixels and normalize
EXIF orientation. Local inference remains shared, resident and serialized. The new
atomic `completeFor` method keeps model selection and generation under one native
lock, preventing another caller from switching models between those operations.

Public generation uses a bounded thread-specific prompt, one actor at a time,
with foreground-service notification support. Editing/deleting the targeted post,
branch, scene or relevant profile while a draft is running invalidates stale results.
The Stop control requests discard after the current native calculation. This old
native backend does not provide a reliable immediate token-level interruption.

This release does **not** add a new LLM, Vulkan/GPU execution, real token streaming,
or reusable native KV sessions. It is not a measured phone-speed upgrade. A large
CPU model can still be slow. Android force-stop, process termination or device
battery policies can interrupt generation; background execution is not guaranteed.

## Storage and privacy

The original encrypted chat/settings data remains at its existing locations.
New public-world metadata uses a separate atomic AES-GCM file protected with a
new Android Keystore key. Read errors preserve the file rather than resetting it.
The complete feed/comment history is saved; retrieval and rendering limits do not
truncate storage.

Imported images are app-private JPEGs, not individually AES-encrypted. The app
retains network permission for its model downloader; generation itself is local.
No claim of a third-party native-library security audit is made. Keep the source
private: it contains your preserved signing keystore and existing build credentials.

Application ID: `com.localcharacter.chat`. Version: `5.0.0`, code `50`.
The supplied v4.0 signing key is unchanged byte-for-byte. This is intended to permit
an update over builds using that same certificate, but installation/migration was
not tested on a phone. Do not uninstall the old app as a routine upgrade step;
uninstalling removes app-owned chats/models.

## Build and validation

With Java 17, Gradle 8.9, Android SDK platform 35 and build tools installed:

```bash
bash tools/build-android.sh
```

The script runs Gradle unit tests and `:app:assembleDebug`, validates the Android
and arm64 native payload, requires `apksigner verify`, then writes an APK checksum.
Its success output must not be substituted with a source ZIP. The included GitHub
workflow sets up Java/Gradle and executes this script; it has not been run against
this version in the user's repository during this session.

Expected APK on a successful equipped build:
`app/build/outputs/apk/debug/app-debug.apk`.

Host-only validation and exact logs are documented in `TEST_REPORT.md`. Full Android
compilation, native-model execution, installation and UI interaction were **not**
verified. No APK is included in this source archive.
