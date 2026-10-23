package verify

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.scoring.RiskEngine
import com.riskauditor.scoring.RubricLoader
import com.riskauditor.scoring.ScoringInput
import com.riskauditor.trust.DeviceRole
import com.riskauditor.trust.RoleHolders
import com.riskauditor.trust.TrustRegistry
import com.riskauditor.trust.TrustedTool
import com.riskauditor.trust.TrustedToolKind

/**
 * Prints the calibration table.
 *
 * These are the numbers quoted in docs/SCORING_RUBRIC.md. They are produced by
 * running the real engine over the real rubric, so the document cannot quote a
 * score the engine does not actually produce.
 */
fun calibrationTable() {
    val catalog = CatalogLoader.default
    val rubric = RubricLoader.default
    val engine = RiskEngine(rubric, catalog, TrustRegistry.EMPTY)

    val mdmRegistry = TrustRegistry(
        listOf(TrustedTool("com.enterprise.mdm", "Enterprise MDM", TrustedToolKind.MDM))
    )

    data class Row(val name: String, val input: ScoringInput, val engineOverride: RiskEngine? = null)

    fun s(snapshot: AppSnapshot, roles: RoleHolders = RoleHolders.EMPTY) = ScoringInput(snapshot, roles)

    val rows = listOf(
        Row(
            "Clean app (Play, internet only)",
            s(snapshot(app("com.clean", "Clean App"), listOf(perm("android.permission.INTERNET"))))
        ),
        Row(
            "Game (Play, storage + network + boot)",
            s(snapshot(app("com.game.puzzle", "Puzzle Quest Game"), listOf(
                perm("android.permission.INTERNET"),
                perm("android.permission.READ_MEDIA_IMAGES"),
                perm("android.permission.RECEIVE_BOOT_COMPLETED"),
                perm("android.permission.POST_NOTIFICATIONS")
            )))
        ),
        Row(
            "Camera app (Play, camera + mic + location + storage)",
            s(snapshot(app("com.photo.camera", "Pro Camera Lens"), listOf(
                perm("android.permission.CAMERA"), perm("android.permission.RECORD_AUDIO"),
                perm("android.permission.ACCESS_FINE_LOCATION"),
                perm("android.permission.READ_MEDIA_IMAGES"),
                perm("android.permission.INTERNET")
            )))
        ),
        Row(
            "Messenger (Play, broad access, no role)",
            s(snapshot(app("com.chat.messenger", "Chat Messenger"), listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
                perm("android.permission.SEND_SMS"), perm("android.permission.READ_CONTACTS"),
                perm("android.permission.WRITE_CONTACTS"), perm("android.permission.CAMERA"),
                perm("android.permission.RECORD_AUDIO"), perm("android.permission.READ_MEDIA_IMAGES"),
                perm("android.permission.READ_PHONE_STATE"), perm("android.permission.ACCESS_FINE_LOCATION"),
                perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED"),
                perm("android.permission.FOREGROUND_SERVICE"), perm("android.permission.POST_NOTIFICATIONS")
            )))
        ),
        Row(
            "Messenger holding the default-SMS role",
            s(
                snapshot(app("com.chat.messenger", "Chat Messenger"), listOf(
                    perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
                    perm("android.permission.SEND_SMS"), perm("android.permission.READ_CONTACTS"),
                    perm("android.permission.CAMERA"), perm("android.permission.RECORD_AUDIO"),
                    perm("android.permission.INTERNET")
                )),
                RoleHolders.of("com.chat.messenger" to setOf(DeviceRole.DEFAULT_SMS))
            )
        ),
        Row(
            "Every non-special permission granted (Play)",
            s(snapshot(
                app("com.greedy", "Greedy"),
                catalog.entries.filter { !it.special }.map { perm(it.permission) }
            ))
        ),
        Row(
            "Verified MDM with device admin (Play)",
            s(snapshot(
                app("com.enterprise.mdm", "Enterprise MDM"),
                listOf(perm("android.permission.INTERNET"), perm("android.permission.READ_PHONE_STATE")),
                listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED)
            )),
            RiskEngine(rubric, catalog, mdmRegistry)
        ),
        Row(
            "Unverified app with device admin (sideloaded)",
            s(snapshot(
                app("com.unknown.admin", "Helper", installer = InstallerKind.SIDELOADED),
                listOf(perm("android.permission.INTERNET")),
                listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED)
            ))
        ),
        Row(
            "Torch holding SMS + contacts + call log (sideloaded)",
            s(snapshot(
                app("com.bright.torch", "Super Torch Flashlight", installer = InstallerKind.SIDELOADED),
                listOf(
                    perm("android.permission.READ_SMS"), perm("android.permission.READ_CONTACTS"),
                    perm("android.permission.READ_CALL_LOG"), perm("android.permission.INTERNET")
                )
            ))
        ),
        Row(
            "Banking trojan (accessibility + overlay, sideloaded, hidden)",
            s(snapshot(
                app("com.fake.updater", "System Update", installer = InstallerKind.SIDELOADED, hasLauncherIcon = false),
                listOf(
                    perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
                    perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED")
                ),
                listOf(
                    SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                    SpecialAccessKind.OVERLAY to AccessState.ENABLED
                )
            ))
        ),
        Row(
            "Stalkerware (hidden, camera/mic, background location, boot)",
            s(snapshot(
                app("com.sys.service", "Sync Service", installer = InstallerKind.ADB_OR_NONE, hasLauncherIcon = false),
                listOf(
                    perm("android.permission.CAMERA"), perm("android.permission.RECORD_AUDIO"),
                    perm("android.permission.ACCESS_BACKGROUND_LOCATION"),
                    perm("android.permission.ACCESS_FINE_LOCATION"),
                    perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED")
                )
            ))
        ),
        Row(
            "Clean app with a MALICIOUS hash verdict",
            s(
                snapshot(app("com.clean", "Clean App"), listOf(perm("android.permission.INTERNET")))
                    .copy(hashVerdict = HashVerdict.Malicious("local", 7))
            )
        )
    )

    println()
    println("─".repeat(94))
    println("  CALIBRATION TABLE — produced by the real engine over the real rubric.json")
    println("─".repeat(94))
    println(String.format("  %-58s %6s %10s %8s", "PROFILE", "SCORE", "BAND", "REASONS"))
    println("  " + "─".repeat(90))

    rows.forEach { row ->
        val result = (row.engineOverride ?: engine).score(row.input)
        println(
            String.format(
                "  %-58s %6d %10s %8d",
                row.name.take(58), result.score, result.band.name, result.reasons.size
            )
        )
    }
    println("─".repeat(94))

    // Show the full reason list for the headline demo case.
    println()
    println("  WORKED EXAMPLE — torch app holding SMS, sideloaded")
    println("  " + "─".repeat(90))
    val torch = rows.first { it.name.startsWith("Torch") }
    val torchResult = engine.score(torch.input)
    println("  score=${torchResult.score}  band=${torchResult.band}  bandFloorRule=${torchResult.bandFloorRule}")
    torchResult.reasons.forEachIndexed { i, r ->
        println("   ${i + 1}. [${r.points.toString().padStart(3)}] ${r.headline}")
        println("        ${r.detail}")
    }
    println()
}
