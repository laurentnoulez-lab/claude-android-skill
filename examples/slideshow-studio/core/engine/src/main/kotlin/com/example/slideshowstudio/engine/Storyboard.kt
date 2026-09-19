package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/** One photo inside a scene: which photo, where, how it moves, and how much of it may be cut. */
data class SlotPlan(
    val photoIndex: Int,
    val rect: NormRect,
    val motion: MotionSpec,
    /** 1 = the photo covers its slot, 0 = the whole photo stays visible. See [PhotoFraming]. */
    val fill: Float,
    /** Zoom ceiling that keeps the focus area visible for the whole scene. */
    val maxZoom: Float,
) {
    val coversSlot: Boolean get() = fill >= PhotoFraming.FULL_FILL
}

/** What fills the canvas behind the photos of a scene. */
data class SceneBackground(
    val color: Int,
    /** Photo used as a blurred backdrop, or null for a plain colour. */
    val photoIndex: Int? = null,
    /** Slow movement applied to the backdrop. */
    val motion: MotionSpec? = null,
    /** How much the backdrop is darkened so the photos in front stay readable. */
    val dim: Float = 0f,
)

/** A composition shown for [durationSeconds], plus the transition that brings it in. */
data class Scene(
    val index: Int,
    val layoutId: String,
    val slots: List<SlotPlan>,
    val transitionIn: TransitionSpec?,
    val background: SceneBackground,
    val durationSeconds: Float,
    /** Holds a single photo the user marked as important: alone, longer, and more calmly animated. */
    val isHighlight: Boolean = false,
) {
    val photoCount: Int get() = slots.size
}

/** The full plan of the video. Deterministic for a given photo list + settings (including the seed). */
data class Storyboard(
    val settings: SlideshowSettings,
    val scenes: List<Scene>,
) {
    /** Display duration the user chose. Individual scenes may hold a little longer than this. */
    val sceneDurationSeconds: Float get() = settings.sceneDurationSeconds
    val transitionDurationSeconds: Float get() = settings.effectiveTransitionSeconds
    val canvasAspect: Float get() = settings.canvasAspect
    val isEmpty: Boolean get() = scenes.isEmpty()

    /** Where each scene starts: scenes no longer all last the same time. */
    val sceneStartTimes: List<Float> = run {
        var start = 0f
        scenes.map { scene ->
            val current = start
            start += scene.durationSeconds
            current
        }
    }

    val totalDurationSeconds: Float =
        (sceneStartTimes.lastOrNull() ?: 0f) + (scenes.lastOrNull()?.durationSeconds ?: 0f)

    val frameCount: Int get() = (totalDurationSeconds * settings.fps).roundToInt()

    /** Index of the scene playing at [seconds], clamped to the timeline. */
    fun sceneIndexAt(seconds: Float): Int {
        if (scenes.isEmpty()) return 0
        val t = seconds.coerceIn(0f, totalDurationSeconds)
        var index = sceneStartTimes.binarySearch { start -> start.compareTo(t) }
        if (index < 0) index = -index - 2
        return index.coerceIn(0, scenes.size - 1)
    }

    /** Duration of the transition that brings scene [index] in, never more than half of a scene. */
    fun transitionDurationFor(index: Int): Float {
        if (index <= 0 || index >= scenes.size) return 0f
        return minOf(
            transitionDurationSeconds,
            scenes[index].durationSeconds * 0.5f,
            scenes[index - 1].durationSeconds * 0.5f,
        )
    }
}

/**
 * Turns a list of photos into a storyboard.
 *
 * Rules enforced here, in order of priority:
 *  1. every photo appears exactly once, in exactly one scene;
 *  2. the photo order follows the mode the user chose;
 *  3. a scene never holds more photos than the mode allows;
 *  4. compositions are chosen to suit the photos they hold;
 *  5. compositions, movements and transitions vary from one scene to the next.
 */
object StoryboardBuilder {

    /** Largest number of photos a single scene can hold, whatever a group asks for. */
    const val MAX_PHOTOS_PER_SCENE = 4

    /** How far a photo may travel from its original position in adaptive order. */
    const val MAX_ADAPTIVE_SHIFT = 2

    /** How far ahead adaptive order may look for a better fitting photo. */
    private const val ADAPTIVE_LOOKAHEAD = 2

    /**
     * How much better a reordered scene has to be before adaptive order accepts it. Without this the
     * engine would take the best fitting photos every single time and the result would read as a
     * shuffle; the user asked for their order, with the occasional adjustment.
     */
    private const val ADAPTIVE_MIN_GAIN = 0.80f

    /**
     * How much worse than the best fitting composition a candidate may be and still be considered.
     * Without it the engine would always pick the single best fit and every scene with the same
     * photo shapes would look identical; with it, variety is chosen among the compositions that
     * actually suit the photos.
     */
    private const val TEMPLATE_TOLERANCE = 0.22f

    /**
     * What the engine schedules: either one photo on its own, or a set of photos the user tied
     * together and which must be shown side by side in a single scene.
     */
    private data class PhotoUnit(
        val photoIndexes: List<Int>,
        val kind: Kind,
    ) {
        enum class Kind { FREE, GROUP, IMPORTANT }

        /** Free units are the only ones the engine may combine with their neighbours. */
        val isFree: Boolean get() = kind == Kind.FREE
        val first: Int get() = photoIndexes.first()
    }

    /**
     * Splits the photos into schedulable units.
     *
     * A group takes the place of its first photo, so the sequence still reads in the user's order.
     * An important photo is alone by definition, which wins over any group it was put in — the app
     * prevents the combination, and the engine does not depend on it having done so.
     */
    private fun buildUnits(photos: List<PhotoRef>, settings: SlideshowSettings, random: Random): List<PhotoUnit> {
        val members = linkedMapOf<String, MutableList<Int>>()
        photos.forEachIndexed { index, photo ->
            val group = photo.groupId
            if (group != null && !photo.isImportant) {
                members.getOrPut(group) { mutableListOf() } += index
            }
        }

        val emitted = mutableSetOf<String>()
        val units = mutableListOf<PhotoUnit>()
        photos.forEachIndexed { index, photo ->
            val group = photo.groupId?.takeIf { !photo.isImportant }
            when {
                photo.isImportant -> units += PhotoUnit(listOf(index), PhotoUnit.Kind.IMPORTANT)
                group == null -> units += PhotoUnit(listOf(index), PhotoUnit.Kind.FREE)
                emitted.add(group) -> {
                    // A group larger than a scene can hold is shown as consecutive full scenes
                    // rather than being silently broken up out of order.
                    members.getValue(group).chunked(MAX_PHOTOS_PER_SCENE).forEach { chunk ->
                        units += if (chunk.size == 1) {
                            PhotoUnit(chunk, PhotoUnit.Kind.FREE)
                        } else {
                            PhotoUnit(chunk, PhotoUnit.Kind.GROUP)
                        }
                    }
                }
            }
        }

        return if (settings.photoOrder == PhotoOrder.SHUFFLE) units.shuffled(random) else units
    }

    fun build(photos: List<PhotoRef>, rawSettings: SlideshowSettings): Storyboard {
        val settings = rawSettings.sanitized()
        if (photos.isEmpty()) return Storyboard(settings, emptyList())

        val random = Random(settings.seed)
        val canvasAspect = settings.canvasAspect
        val templatesByCount = (1..4).associateWith { LayoutCatalog.templatesFor(it, canvasAspect) }

        // The engine schedules units, not photos: a group travels through the whole pipeline as one.
        val queue = ArrayDeque(buildUnits(photos, settings, random))
        val originalPosition = buildMap {
            queue.forEachIndexed { position, unit ->
                unit.photoIndexes.forEach { photoIndex -> put(photoIndex, position) }
            }
        }

        val scenes = mutableListOf<Scene>()
        var placed = 0
        var previousCount = 0
        var previousTemplateId: String? = null
        val lastTemplateForCount = mutableMapOf<Int, String>()
        var previousTransition: TransitionKind? = null
        var previousMotions: Set<MotionKind> = emptySet()
        var previousBackgroundColor: Int? = null

        var previousWasHighlight = false

        while (queue.isNotEmpty()) {
            val head = queue.first()
            // An important photo takes the whole scene, and so does a group — for opposite reasons,
            // but with the same consequence: the count the engine would have liked gives way.
            val highlight = head.kind == PhotoUnit.Kind.IMPORTANT
            // Units free to share a scene: the run before the next group or important photo.
            val free = queue.takeWhile { it.isFree }

            val naturalPicks: List<Int>
            val count: Int
            if (head.isFree) {
                count = chooseCount(minOf(settings.mode.maxImages, free.size), previousCount, random)
                naturalPicks = free.take(count).map { it.first }
            } else {
                naturalPicks = head.photoIndexes
                count = naturalPicks.size
            }

            val templates = templatesByCount.getValue(count)
            val template = chooseTemplate(
                templates = templates,
                picks = naturalPicks,
                photos = photos,
                canvasAspect = canvasAspect,
                previousTemplateId = previousTemplateId,
                lastForThisCount = lastTemplateForCount[count],
                random = random,
            )

            val ordered = takePhotos(
                queue = queue,
                free = free,
                naturalPicks = naturalPicks,
                count = count,
                shareable = head.isFree,
                template = template,
                photos = photos,
                canvasAspect = canvasAspect,
                order = settings.photoOrder,
                placed = placed,
                originalPosition = originalPosition,
            )
            placed += if (head.isFree) count else 1

            val usedMotions = mutableSetOf<MotionKind>()
            val slots = ordered.mapIndexed { slotIndex, photoIndex ->
                val rect = template.slots[slotIndex]
                val photo = photos[photoIndex]
                val kind = if (highlight) {
                    MotionKind.ZOOM_IN
                } else {
                    MotionFactory.pickKind(random, usedMotions + previousMotions, canvasAspect)
                }
                usedMotions += kind
                val fill = CropPlanner.fillFor(
                    mode = settings.cropMode,
                    photo = photo,
                    slot = rect,
                    canvasAspect = canvasAspect,
                    photosInScene = ordered.size,
                )
                val cropAspect = PhotoFraming.cropAspect(photo.aspect, rect.pixelAspect(canvasAspect), fill)
                SlotPlan(
                    photoIndex = photoIndex,
                    rect = rect,
                    motion = if (highlight) {
                        MotionFactory.createHighlight(random, canvasAspect)
                    } else {
                        MotionFactory.create(kind, random)
                    },
                    fill = fill,
                    maxZoom = PhotoFraming.maxZoomKeepingFocus(photo, cropAspect),
                )
            }

            // A highlight deserves a calm arrival, and the scene that follows it deserves a calm
            // departure: both transitions come from the quiet set.
            val transition = if (scenes.isEmpty()) {
                null
            } else {
                val kind = TransitionFactory.pickKind(
                    random = random,
                    previous = previousTransition,
                    canvasAspect = canvasAspect,
                    elegantOnly = highlight || previousWasHighlight,
                )
                previousTransition = kind
                TransitionFactory.create(kind, random)
            }

            val background = buildBackground(
                settings = settings,
                slots = slots,
                photos = photos,
                random = random,
                previousColor = previousBackgroundColor,
            )
            previousBackgroundColor = background.color

            scenes += Scene(
                index = scenes.size,
                layoutId = template.id,
                slots = slots,
                transitionIn = transition,
                background = background,
                // Held a little longer than the rest: enough to register, not enough to drag.
                durationSeconds = if (highlight) {
                    settings.sceneDurationSeconds * HIGHLIGHT_DURATION_FACTOR
                } else {
                    settings.sceneDurationSeconds
                },
                isHighlight = highlight,
            )

            previousCount = count
            previousTemplateId = template.id
            lastTemplateForCount[count] = template.id
            previousMotions = usedMotions
            previousWasHighlight = highlight
        }

        return Storyboard(settings, scenes)
    }

    /**
     * Removes the photos of the next scene from [queue] and returns them in slot order.
     *
     * In strict and shuffled order they are simply the next ones. In adaptive order the engine may
     * look a couple of positions ahead for a photo that suits the composition better, never letting
     * any photo drift more than [MAX_ADAPTIVE_SHIFT] positions from where the user put it.
     */
    private fun takePhotos(
        queue: ArrayDeque<PhotoUnit>,
        free: List<PhotoUnit>,
        naturalPicks: List<Int>,
        count: Int,
        shareable: Boolean,
        template: LayoutTemplate,
        photos: List<PhotoRef>,
        canvasAspect: Float,
        order: PhotoOrder,
        placed: Int,
        originalPosition: Map<Int, Int>,
    ): List<Int> {
        val chosen: List<Int> = when {
            !shareable -> naturalPicks
            // Adaptive order looks ahead, but never past a group or an important photo: pulling one
            // into a shared composition, or breaking it up, is exactly what it must not do.
            order == PhotoOrder.ADAPTIVE && free.size > count ->
                pickAdaptive(free.map { it.first }, count, template, photos, canvasAspect, placed, originalPosition)

            else -> naturalPicks
        }
        queue.removeAll { unit -> unit.photoIndexes.any { it in chosen } }
        return bestAssignment(chosen, template.slots, photos, canvasAspect).first
    }

    private fun pickAdaptive(
        queue: List<Int>,
        count: Int,
        template: LayoutTemplate,
        photos: List<PhotoRef>,
        canvasAspect: Float,
        placed: Int,
        originalPosition: Map<Int, Int>,
    ): List<Int> {
        val window = queue.take(minOf(count + ADAPTIVE_LOOKAHEAD, queue.size))
        val natural = queue.take(count)

        // A photo left out now would land at least `count` positions later: once that would push it
        // past the limit, it has to be part of this scene.
        val forced = window.filter { placed + count - (originalPosition[it] ?: 0) > MAX_ADAPTIVE_SHIFT }
        if (forced.size >= count) return natural

        val naturalCost = bestAssignment(natural, template.slots, photos, canvasAspect).second
        var best: List<Int>? = null
        var bestCost = naturalCost - ADAPTIVE_MIN_GAIN
        combinations(window, count) { candidate ->
            if (!candidate.containsAll(forced)) return@combinations
            val sorted = candidate.sortedBy { originalPosition[it] ?: 0 }
            val withinLimit = sorted.withIndex().all { (offset, index) ->
                abs((placed + offset) - (originalPosition[index] ?: 0)) <= MAX_ADAPTIVE_SHIFT
            }
            if (!withinLimit) return@combinations
            if (sorted == natural) return@combinations
            val cost = bestAssignment(sorted, template.slots, photos, canvasAspect).second
            if (cost < bestCost) {
                bestCost = cost
                best = sorted
            }
        }
        return best ?: natural
    }

    /** Uniform pick inside the allowed range, excluding the previous count when there is a choice. */
    private fun chooseCount(maxCount: Int, previousCount: Int, random: Random): Int {
        if (maxCount <= 1) return 1
        val candidates = (1..maxCount).filter { it != previousCount }
        val pool = candidates.ifEmpty { (1..maxCount).toList() }
        return pool[random.nextInt(pool.size)]
    }

    /**
     * Picks the composition for a scene.
     *
     * The choice is made against the photos that will actually fill it, which is what keeps a
     * landscape photo out of a narrow column in a portrait video and a portrait photo out of a flat
     * band in a landscape one: the shape distance between a photo and its slot is exactly the cost
     * being minimised. Variety is then taken among the compositions that fit nearly as well, rather
     * than across all of them.
     */
    private fun chooseTemplate(
        templates: List<LayoutTemplate>,
        picks: List<Int>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
        previousTemplateId: String?,
        lastForThisCount: String?,
        random: Random,
    ): LayoutTemplate {
        val avoided = setOfNotNull(previousTemplateId, lastForThisCount)
        val pool = templates.filterNot { it.id in avoided }
            .ifEmpty { templates.filterNot { it.id == previousTemplateId } }
            .ifEmpty { templates }
        if (picks.isEmpty() || pool.size == 1) return pool[random.nextInt(pool.size)]

        val scored = pool.map { template ->
            template to bestAssignment(picks, template.slots, photos, canvasAspect).second
        }
        val best = scored.minOf { it.second }
        val suitable = scored.filter { it.second <= best + TEMPLATE_TOLERANCE }.map { it.first }
        return suitable[random.nextInt(suitable.size)]
    }

    private fun buildBackground(
        settings: SlideshowSettings,
        slots: List<SlotPlan>,
        photos: List<PhotoRef>,
        random: Random,
        previousColor: Int?,
    ): SceneBackground = when (settings.backgroundMode) {
        BackgroundMode.SOLID -> SceneBackground(color = settings.backgroundColor)

        BackgroundMode.RANDOM -> SceneBackground(
            color = Palette.randomBackground(random, previousColor?.let(Palette::hueOf)),
        )

        BackgroundMode.BLURRED_PHOTO -> {
            val photoIndex = chooseBackdrop(slots, photos, settings.canvasAspect, random)
            SceneBackground(
                color = Palette.DEFAULT_BACKGROUND,
                photoIndex = photoIndex,
                motion = MotionFactory.createBackdrop(random),
                dim = BACKDROP_DIM,
            )
        }
    }

    /**
     * Picks which photo of the scene becomes the blurred backdrop: the one that fills the canvas
     * with the least cropping, or the runner-up, so the choice is never mechanically the first slot.
     */
    private fun chooseBackdrop(
        slots: List<SlotPlan>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
        random: Random,
    ): Int {
        val ranked = slots.map { it.photoIndex }.sortedByDescending { index ->
            val (width, height) = PhotoFraming.coverSize(photos[index].aspect, canvasAspect)
            width * height
        }
        if (ranked.size == 1) return ranked.first()
        return if (random.nextInt(3) == 0) ranked[1] else ranked[0]
    }

    /**
     * Matches photos with slots so that portrait photos land in tall slots and landscape photos in
     * wide ones. With at most four photos per scene, testing every permutation is cheap and gives
     * the assignment that crops the least.
     *
     * Note that this reorders photos *within* a scene only: all of them are shown at the same time,
     * so it changes the composition, never the sequence the viewer perceives.
     */
    internal fun bestAssignment(
        picks: List<Int>,
        slots: List<NormRect>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
    ): Pair<List<Int>, Float> {
        if (picks.size <= 1) {
            return picks to mismatch(picks, slots, photos, canvasAspect)
        }
        var best = picks
        var bestCost = Float.MAX_VALUE
        permutations(picks) { candidate ->
            val cost = mismatch(candidate, slots, photos, canvasAspect)
            if (cost < bestCost) {
                bestCost = cost
                best = candidate.toList()
            }
        }
        return best to bestCost
    }

    /** How far each photo's shape is from the shape of the slot it would occupy. */
    private fun mismatch(
        candidate: List<Int>,
        slots: List<NormRect>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
    ): Float {
        var cost = 0f
        for (i in candidate.indices) {
            val slotAspect = slots[i].pixelAspect(canvasAspect)
            cost += abs(ln(photos[candidate[i]].aspect / slotAspect))
        }
        return cost
    }

    internal fun assignPhotosToSlots(
        picks: List<Int>,
        slots: List<NormRect>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
    ): List<Int> = bestAssignment(picks, slots, photos, canvasAspect).first

    private fun permutations(items: List<Int>, onPermutation: (List<Int>) -> Unit) {
        val working = items.toMutableList()
        fun recurse(index: Int) {
            if (index == working.size) {
                onPermutation(working)
                return
            }
            for (i in index until working.size) {
                working[index] = working[i].also { working[i] = working[index] }
                recurse(index + 1)
                working[index] = working[i].also { working[i] = working[index] }
            }
        }
        recurse(0)
    }

    private fun combinations(items: List<Int>, size: Int, onCombination: (List<Int>) -> Unit) {
        if (size > items.size) return
        val current = mutableListOf<Int>()
        fun recurse(start: Int) {
            if (current.size == size) {
                onCombination(current.toList())
                return
            }
            for (i in start until items.size) {
                if (items.size - i < size - current.size) break
                current += items[i]
                recurse(i + 1)
                current.removeAt(current.lastIndex)
            }
        }
        recurse(0)
    }

    private const val BACKDROP_DIM = 0.34f

    /** How much longer an important photo stays on screen. */
    private const val HIGHLIGHT_DURATION_FACTOR = 1.25f
}
