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
    println("PASS: $assertions offline assertions")
}
