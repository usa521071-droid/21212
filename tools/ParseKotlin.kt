import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.config.CompilerConfiguration
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
fun main(args:Array<String>) {
    val disposable=Disposer.newDisposable()
    try {
        val env=KotlinCoreEnvironment.createForProduction(disposable,CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory=KtPsiFactory(env.project,false)
        var errors=0
        for(path in args) {
            val f=File(path)
            val psi=factory.createFile(f.name,f.readText())
            for(e in PsiTreeUtil.collectElementsOfType(psi,PsiErrorElement::class.java)) {
                System.err.println("${f.name}:${e.textRange.startOffset}: ${e.errorDescription}"); errors++
            }
        }
        check(errors==0){"$errors syntax errors"}
        println("Parsed ${args.size} Kotlin files. Syntax only, not Android compilation.")
    } finally { Disposer.dispose(disposable) }
}
