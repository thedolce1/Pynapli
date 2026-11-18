package com.pynapli.server.verification

/** One pose the selfie must show. [key] is stored with the attempt; [instruction] is shown to the user. */
data class PhotoVerificationPose(
    val key: String,
    val instruction: String,
)

/**
 * The fixed, server-side pose list for selfie verification. The server picks one at random per
 * challenge, so a stolen or old photo is unlikely to match. Keys are stored on each attempt; removing
 * a key only stops new challenges using it (old attempts still show the key to reviewers).
 */
object PhotoVerificationPoses {
    val all: List<PhotoVerificationPose> =
        listOf(
            PhotoVerificationPose("two_fingers", "Hold up two fingers next to your face."),
            PhotoVerificationPose("touch_ear", "Touch your ear with one hand."),
            PhotoVerificationPose("thumbs_up", "Give a thumbs up next to your face."),
            PhotoVerificationPose("hand_on_chin", "Rest your chin on your hand."),
            PhotoVerificationPose("point_up", "Point up with one finger beside your face."),
            PhotoVerificationPose("open_palm", "Show an open palm next to your face."),
            PhotoVerificationPose("touch_nose", "Touch your nose with one finger."),
            PhotoVerificationPose("hand_on_head", "Put one hand on top of your head."),
        )

    fun byKey(key: String): PhotoVerificationPose? = all.firstOrNull { it.key == key }
}
