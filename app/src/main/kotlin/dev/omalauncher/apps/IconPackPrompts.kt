package dev.omalauncher.apps

import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.theme.StyleStore
import java.io.File

/**
 * Prompts for the default coding agent to suggest pack icons for apps missing from appfilter.
 * The agent runs in the guest, which sees the style directory at ~/[StyleStore.GUEST_DIR], so the
 * brief and [IconOverrides.SUGGESTIONS] are named there rather than by their path on the phone.
 * The launcher reviews the file before merging it into [IconOverrides.FILE].
 */
object IconPackPrompts {
    const val BRIEF = "icon-suggest-brief.txt"

    fun suggestMissingCommand(agentBin: String): String {
        val prompt =
            "Read $BRIEF. It lists Android apps that have no icon in the " +
                "current icon pack, and the pack's drawable names. Suggest one drawable name per app. " +
                "Write ONLY a TOML file to ${IconOverrides.SUGGESTIONS} using rows like:\n" +
                "[[override]]\ncomponent = \"package/activity\"\ndrawable = \"drawable_name\"\n\n" +
                "Use component values exactly as given in the brief. Prefer drawables whose names " +
                "match the app. Do not edit other files. When finished, say how many suggestions you wrote."
        return "cd ~/${StyleStore.GUEST_DIR} && ${agentInvocation(agentBin, prompt)}; exec bash"
    }

    /** Staging brief the agent reads: unmapped apps and the drawable catalog. */
    fun writeBrief(
        file: File,
        packPackage: String,
        apps: List<Pair<String, String>>,
        drawables: List<String>,
    ) {
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("Icon pack: ").append(packPackage).append("\n\n")
        sb.append("Apps missing a pack icon (label | component):\n")
        for ((label, component) in apps) {
            sb.append("- ").append(label).append(" | ").append(component).append('\n')
        }
        sb.append("\nDrawable names in the pack (").append(drawables.size).append("):\n")
        for (name in drawables) sb.append("- ").append(name).append('\n')
        file.writeText(sb.toString())
    }
}
