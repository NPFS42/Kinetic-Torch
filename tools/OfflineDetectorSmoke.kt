import com.npfs.stars.kinetictorch.ChopDetector
import com.npfs.stars.kinetictorch.GravityHighPass

fun main() {
    var assertions = 0
    fun checkCase(pass: Boolean, label: String) {
        check(pass) { label }
        println("PASS $label")
        assertions++
    }
    fun detector() = ChopDetector(forceThreshold=18.5f, chopWindowMs=650L, cooldownMs=1000L)
    run {
        val d = detector()
        checkCase(!d.onLinearAcceleration(25f,0f,0f,0L), "first spike not toggle")
        checkCase(!d.onLinearAcceleration(.5f,0f,0f,100L), "quiet valley not toggle")
        checkCase(d.onLinearAcceleration(25f,0f,0f,300L), "valid second spike toggles")
    }
    run {
        val d = detector()
        d.onLinearAcceleration(25f,0f,0f,0L)
        d.onLinearAcceleration(.5f,0f,0f,100L)
        checkCase(!d.onLinearAcceleration(25f,0f,0f,800L), "expired gesture rejected")
    }
    run {
        val d = detector()
        d.onLinearAcceleration(25f,0f,0f,0L)
        d.onLinearAcceleration(.5f,0f,0f,100L)
        checkCase(!d.onLinearAcceleration(25f,0f,0f,150L), "recoil rejected")
    }
    run {
        val d = detector()
        d.onLinearAcceleration(25f,0f,0f,0L)
        d.onLinearAcceleration(.5f,0f,0f,100L)
        d.onLinearAcceleration(25f,0f,0f,300L)
        checkCase(!d.onLinearAcceleration(25f,0f,0f,500L), "cooldown respected")
    }
    checkCase(!ChopDetector.isChopPlane(1f,1f,30f), "z-axis ignored")
    run {
        val f = GravityHighPass()
        val out = FloatArray(3)
        checkCase(!f.filter(0f,0f,9.81f,0L,out), "high-pass warmup")
        checkCase(f.filter(25f,0f,9.81f,500L,out), "high-pass ready")
    }
    run {
        // Two full gestures: an early OFF attempt is suppressed by design,
        // while a fresh gesture after the 1,000 ms cooldown is recognized.
        val d = detector()
        checkCase(!d.onLinearAcceleration(24f,0f,0f,0L), "ON first chop")
        checkCase(!d.onLinearAcceleration(0f,0f,0f,90L), "ON quiet valley")
        checkCase(d.onLinearAcceleration(25f,0f,0f,300L), "ON second chop")
        checkCase(!d.onLinearAcceleration(0f,0f,0f,500L), "within cooldown quiet")
        checkCase(!d.onLinearAcceleration(23f,0f,0f,900L), "early OFF first chop suppressed")
        checkCase(!d.onLinearAcceleration(0f,0f,0f,980L), "early OFF valley suppressed")
        checkCase(!d.onLinearAcceleration(24f,0f,0f,1200L), "early OFF not a complete gesture")
        checkCase(!d.onLinearAcceleration(0f,0f,0f,1350L), "reset valley")
        checkCase(!d.onLinearAcceleration(23f,0f,0f,1460L), "fresh OFF first chop")
        checkCase(!d.onLinearAcceleration(0f,0f,0f,1530L), "fresh OFF quiet valley")
        checkCase(d.onLinearAcceleration(24f,0f,0f,1750L), "fresh OFF second chop recognized")
    }
    println("PASS: $assertions offline assertions")
}
