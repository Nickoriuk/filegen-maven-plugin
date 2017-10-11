package io.redcastle.maven.plugin.filegen

import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.DirectoryScanner
import org.w3c.dom.Document

import javax.script.ScriptEngineManager
import java.io.*
import java.util.function.Function
import javax.script.ScriptEngine
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Generate files from kotlin scripts in the source directory.
 */
@Mojo(name = "generate-files", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
class GenerateFilesMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project.build.directory}/classes")
    private lateinit var outputDirectory: File

    @Parameter(defaultValue = "\${project.build.sourceDirectory}")
    private lateinit var sourceDirectory: File

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    /**
     * A map of class names, where the key is the type of the class to be transformed to a string, and the value is the
     * name of a class who implements [Function<X, String>][Function], where the type of X is equal to the type given
     * as a key to this map.
     *
     * An example entry is `example.model.ComplexObject`, `example.model.ComplexObjectConverter` where
     * ComplexObjectConverter implements Function with the generic types `ComplexObject, String`
     */
    @Parameter(property = "transformers")
    private lateinit var transformers: MutableMap<String, String>

    @Parameter(property = "sourceFileExtension", defaultValue = ".xml.kts")
    private lateinit var fileExtension: String

    @Parameter(property = "scriptEngineExtension", defaultValue = "kts")
    private lateinit var engineExtension: String

    @Parameter(property = "destinationFileExtension", defaultValue = ".xml")
    private lateinit var destinationFileExtension: String

    @Parameter(defaultValue = "\${project.build.sourceEncoding}")
    private lateinit var encoding: String

    private val fileAntMatcher: String get() = "**/*$fileExtension"

    private lateinit var classMap: Map<Class<out Any?>, Class<out Function<Any, String>>>

    @Throws(MojoExecutionException::class)
    override fun execute() {
        setupClassPath()

        // Prevent original .kts files from being put in the output
        project.resources.forEach {
            it.excludes.add(fileAntMatcher)
        }

        classMap = transformers.convert({Class.forName(it)}, {Class.forName(it).asSubclass(Function::class.java) as Class<Function<Any, String>>})
        classMap.forEach { (key, value) ->
            log.info("Found transformer: $key=$value")
        }
        val engine = ScriptEngineManager().getEngineByExtension(engineExtension)
        val scriptFilePaths = getFileLocations()
        for (scriptPath in scriptFilePaths) {
            log.info("Found file to convert at $scriptPath")
            val script = File(sourceDirectory, scriptPath)
            try {
                val stringResult: String = evaluateScript(engine, script)

                val outFile = File(outputDirectory, scriptPath.replace(fileExtension, destinationFileExtension))
                outFile.parentFile.mkdirs()
                BufferedWriter(OutputStreamWriter(FileOutputStream(outFile), encoding)).use {
                    it.write(stringResult)
                }

            } catch (e: Exception) {
                throw MojoExecutionException(e.message, e)
            }
        }
    }

    private fun evaluateScript(engine: ScriptEngine, script: File): String {
        val result = try {
            engine.eval(FileReader(script))
        } catch (e: Exception) {
            throw MojoExecutionException("An error occurred evaluating the script at ${script.path}", e)
        }

        return when (result) {
            result == null -> throw MojoExecutionException("Received a null result from the script ${script.absolutePath}")
            is String -> result
            is Document -> result.stringContents
            else -> {
                val function = classMap[classMap.keys.firstOrNull { it.isInstance(result) }]?.newInstance()
                if (function != null) {
                    function.apply(result)
                } else throw MojoExecutionException("Expected result of type String or Document, or registered serializer, got ${result.javaClass} for ${script.path}")
            }
        }
    }

    @Throws(MojoExecutionException::class)
    private fun setupClassPath() {
        try {
            val pluginDescriptor = pluginContext["pluginDescriptor"] as PluginDescriptor
            val classRealm = pluginDescriptor.classRealm
            project.dependencyArtifacts
                    .map { it.file.toURI().toURL() }
                    .onEach { log.info("path=$it")}
                    .forEach(classRealm::addURL)
            classRealm.addURL(outputDirectory.toURI().toURL())
        } catch (e: Exception) {
            throw MojoExecutionException("Unable to add the project's classpath to the plugin's class realm.", e)
        }
    }

    /**
     * Gets the file of files in the source directory which can be converted.
     */
    private fun getFileLocations(): List<String> {
        val scanner = DirectoryScanner().apply {
            setIncludes(arrayOf("**/*$fileExtension"))
            basedir = sourceDirectory
            scan()
        }
        return scanner.includedFiles.asList()
    }

    private val Document.stringContents: String get() {
        val transformer = TransformerFactory.newInstance().newTransformer()
        val streamResult = StreamResult(StringWriter())
        val source = DOMSource(this)
        transformer.transform(source, streamResult)
        return streamResult.writer.toString()
    }

    private inline fun <OK,OV,K,V> Map<OK,OV>.convert(keyConverter: (OK) -> K, valueConverter: (OV) -> V): Map<K,V> {
        val map = mutableMapOf<K, V>()
        entries.forEach { (key, value) ->
            map[keyConverter(key)] = valueConverter(value)
        }
        return map
    }
}
