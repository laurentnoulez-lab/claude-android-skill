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

/** A composition shown for one scene duration, plus the transition that brings it in. */
data class Scene(
    val index: Int,
    val layoutId: String,
    val slots: List<SlotPlan>,
    val transitionIn: TransitionSpec?,
    val background: SceneBackground,
) {
    val photoCount: Int get() = slots.size
}

/** The full plan of the video. Deterministic for a given photo list + settings (including the seed). */
data class Storyboard(
    val settings: SlideshowSettings,
    val scenes: List<Scene>,
) {
    val sceneDurationSeconds: Float get() = settings.sceneDurationSeconds
    val transitionDurationSeconds: Float get() = settings.effectiveTransitionSeconds
    val totalDurationSeconds: Float get() = scenes.size * sceneDurationSeconds
    val frameCount: Int get() = (totalDurationSeconds * settings.fps).roundToInt()
    val canvasAspect: Float get() = settings.canvasAspect

    val isEmpty: Boolean get() = scenes.isEmpty()
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

    fun build(photos: List<PhotoRef>, rawSettings: SlideshowSettings): Storyboard {
        val settings = rawSettings.sanitized()
        if (photos.isEmpty()) return Storyboard(settings, emptyList())

        val random = Random(settings.seed)
        val canvasAspect = settings.canvasAspect
        val templatesByCount = (1..4).associateWith { LayoutCatalog.templatesFor(it, canvasAspect) }

        val queue = ArrayDeque(initialOrder(photos, settings, random))
        val originalPosition = queue.withIndex().associate { (position, index) -> index to position }

        val scenes = mutableListOf<Scene>()
        var placed = 0
        var previousCount = 0
        var previousTemplateId: String? = null
        val lastTemplateForCount = mutableMapOf<Int, String>()
        var previousTransition: TransitionKind? = null
        var previousMotions: Set<MotionKind> = emptySet()
        var previousBackgroundColor: Int? = null

        while (queue.isNotEmpty()) {
            val maxCount = minOf(settings.mode.maxImages, photos.size, queue.size)
            val count = chooseCount(maxCount, previousCount, random)
            val templates = templatesByCount.getValue(count)
            val template = chooseTemplate(templates, previousTemplateId, lastTemplateForCount[count], random)

            val ordered = takePhotos(
                queue = queue,
                count = count,
                template = template,
                photos = photos,
                canvasAspect = canvasAspect,
                order = settings.photoOrder,
                placed = placed,
                originalPosition = originalPosition,
            )
            placed += ordered.size

            val usedMotions = mutableSetOf<MotionKind>()
            val slots = ordered.mapIndexed { slotIndex, photoIndex ->
                val rect = template.slots[slotIndex]
                val photo = photos[photoIndex]
                val kind = MotionFactory.pickKind(random, usedMotions + previousMotions, canvasAspect)
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
                    motion = MotionFactory.create(kind, random),
                    fill = fill,
                    maxZoom = PhotoFraming.maxZoomKeepingFocus(photo, cropAspect),
                )
            }

            val transition = if (scenes.isEmpty()) {
                null
            } else {
                val kind = TransitionFactory.pickKind(random, previousTransition, canvasAspect)
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
            )

            previousCount = count
            previousTemplateId = template.id
            lastTemplateForCount[count] = template.id
            previousMotions = usedMotions
        }

        return Storyboard(settings, scenes)
    }

    private fun initialOrder(
        photos: List<PhotoRef>,
        settings: SlideshowSettings,
        random: Random,
    ): List<Int> = when (settings.photoOrder) {
        PhotoOrder.STRICT, PhotoOrder.ADAPTIVE -> photos.indices.toList()
        PhotoOrder.SHUFFLE -> photos.indices.shuffled(random)
    }

    /**
     * Removes the photos of the next scene from [queue] and returns them in slot order.
     *
     * In strict and shuffled order they are simply the next ones. In adaptive order the engine may
     * look a couple of positions ahead for a photo that suits the composition better, never letting
     * any photo drift more than [MAX_ADAPTIVE_SHIFT] positions from where the user put it.
     */
    private fun takePhotos(
        queue: ArrayDeque<Int>,
        count: Int,
        template: LayoutTemplate,
        photos: List<PhotoRef>,
        canvasAspect: Float,
        order: PhotoOrder,
        placed: Int,
        originalPosition: Map<Int, Int>,
    ): List<Int> {
        val chosen: List<Int> = if (order == PhotoOrder.ADAPTIVE && queue.size > count) {
            pickAdaptive(queue, count, template, photos, canvasAspect, placed, originalPosition)
        } else {
            queue.take(count)
        }
        chosen.forEach { queue.remove(it) }
        return bestAssignment(chosen, template.slots, photos, canvasAspect).first
    }

    private fun pickAdaptive(
        queue: ArrayDeque<Int>,
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

    private fun chooseTemplate(
        templates: List<LayoutTemplate>,
        previousTemplateId: String?,
        lastForThisCount: String?,
        random: Random,
    ): LayoutTemplate {
        val avoided = setOfNotNull(previousTemplateId, lastForThisCount)
        val candidates = templates.filterNot { it.id in avoided }
        val pool = candidates.ifEmpty { templates.filterNot { it.id == previousTemplateId }.ifEmpty { templates } }
        return pool[random.nextInt(pool.size)]
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
}
