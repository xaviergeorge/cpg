/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
package de.fraunhofer.aisec.cpg

import de.fraunhofer.aisec.cpg.TestUtils.flattenIsInstance
import de.fraunhofer.aisec.cpg.frontends.CompilationDatabase
import de.fraunhofer.aisec.cpg.graph.Node
import de.fraunhofer.aisec.cpg.graph.TypeManager
import de.fraunhofer.aisec.cpg.graph.declarations.Declaration
import de.fraunhofer.aisec.cpg.graph.declarations.FunctionDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration
import de.fraunhofer.aisec.cpg.graph.statements.expressions.CallExpression
import de.fraunhofer.aisec.cpg.graph.statements.expressions.DeclaredReferenceExpression
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Expression
import de.fraunhofer.aisec.cpg.helpers.SubgraphWalker
import de.fraunhofer.aisec.cpg.helpers.Util
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.apache.commons.lang3.reflect.FieldUtils
import org.mockito.Mockito

object TestUtils {
    fun <S : Node?> findByUniquePredicate(nodes: Collection<S>, predicate: Predicate<S>): S {
        val results = nodes.filter { predicate.test(it) }

        assertEquals(
            1,
            results.size,
            "Expected exactly one node matching the predicate: ${results.joinToString(",") { it.toString() }}",
        )

        val node = results.firstOrNull()
        assertNotNull(node)

        return node
    }

    @Deprecated(
        replaceWith = ReplaceWith("nodes.filter(predicate)"),
        message = "should be replace with kotlin filter"
    )
    fun <S : Node> findByPredicate(nodes: Collection<S>, predicate: (S) -> Boolean): List<S> {
        return nodes.filter(predicate)
    }

    fun <S : Node> findByUniqueName(nodes: Collection<S>, name: String): S {
        return findByUniquePredicate(nodes) { m: S -> m.name == name }
    }

    fun <S : Node> findByName(nodes: Collection<S>, name: String): List<S> {
        return nodes.filter { m: S -> m.name == name }
    }

    /**
     * Like [TestUtils.analyze], but for all files in a directory tree having a specific file
     * extension
     *
     * @param fileExtension All files found in the directory must end on this String. An empty
     * string matches all files
     * @param topLevel The directory to traverse while looking for files to parse
     * @param usePasses Whether the analysis should run passes after the initial phase
     * @param configModifier An optional modifier for the config
     *
     * @return A list of [TranslationUnitDeclaration] nodes, representing the CPG roots
     * @throws Exception Any exception thrown during the parsing process
     */
    @JvmOverloads
    @Throws(Exception::class)
    fun analyze(
        fileExtension: String?,
        topLevel: Path,
        usePasses: Boolean,
        configModifier: Consumer<TranslationConfiguration.Builder>? = null
    ): List<TranslationUnitDeclaration> {
        val files =
            Files.walk(topLevel, Int.MAX_VALUE)
                .map { obj: Path -> obj.toFile() }
                .filter { obj: File -> obj.isFile }
                .filter { f: File -> f.name.endsWith(fileExtension!!) }
                .sorted()
                .collect(Collectors.toList())
        return analyze(files, topLevel, usePasses, configModifier)
    }

    /**
     * Default way of parsing a list of files into a full CPG. All default passes are applied
     *
     * @param topLevel The directory to traverse while looking for files to parse
     * @param usePasses Whether the analysis should run passes after the initial phase
     * @param configModifier An optional modifier for the config
     *
     * @return A list of [TranslationUnitDeclaration] nodes, representing the CPG roots
     * @throws Exception Any exception thrown during the parsing process
     */
    @JvmOverloads
    @Throws(Exception::class)
    fun analyze(
        files: List<File>?,
        topLevel: Path,
        usePasses: Boolean,
        configModifier: Consumer<TranslationConfiguration.Builder>? = null
    ): List<TranslationUnitDeclaration> {
        return analyzeWithResult(files, topLevel, usePasses, configModifier).translationUnits
    }

    /**
     * Default way of parsing a list of files into a full CPG. All default passes are applied
     *
     * @param topLevel The directory to traverse while looking for files to parse
     * @param usePasses Whether the analysis should run passes after the initial phase
     * @param configModifier An optional modifier for the config
     *
     * @return A list of [TranslationUnitDeclaration] nodes, representing the CPG roots
     * @throws Exception Any exception thrown during the parsing process
     */
    @JvmOverloads
    @Throws(Exception::class)
    fun analyzeWithResult(
        files: List<File>?,
        topLevel: Path,
        usePasses: Boolean,
        configModifier: Consumer<TranslationConfiguration.Builder>? = null
    ): TranslationResult {
        val builder =
            TranslationConfiguration.builder()
                .sourceLocations(files)
                .topLevel(topLevel.toFile())
                .loadIncludes(true)
                .disableCleanup()
                .debugParser(true)
                .failOnError(true)
                .typeSystemActiveInFrontend(false)
                .useParallelFrontends(true)
                .defaultLanguages()
        if (usePasses) {
            builder.defaultPasses()
        }
        configModifier?.accept(builder)
        val config = builder.build()
        val analyzer = TranslationManager.builder().config(config).build()
        return analyzer.analyze().get()
    }

    /**
     * Default way of parsing a list of files into a full CPG. All default passes are applied
     *
     * @param builder A [TranslationConfiguration.Builder] which contains the configuration
     * @return A list of [TranslationUnitDeclaration] nodes, representing the CPG roots
     * @throws Exception Any exception thrown during the parsing process
     */
    @Throws(Exception::class)
    fun analyzeWithBuilder(
        builder: TranslationConfiguration.Builder
    ): List<TranslationUnitDeclaration> {
        val config = builder.build()
        val analyzer = TranslationManager.builder().config(config).build()
        return analyzer.analyze().get().translationUnits
    }

    @JvmOverloads
    @Throws(Exception::class)
    fun analyzeAndGetFirstTU(
        files: List<File>?,
        topLevel: Path,
        usePasses: Boolean,
        configModifier: Consumer<TranslationConfiguration.Builder>? = null
    ): TranslationUnitDeclaration {
        val translationUnits = analyze(files, topLevel, usePasses, configModifier)
        return translationUnits.stream().findFirst().orElseThrow()
    }

    @Throws(Exception::class)
    fun analyzeWithCompilationDatabase(
        jsonCompilationDatabase: File,
        usePasses: Boolean,
        configModifier: Consumer<TranslationConfiguration.Builder>? = null
    ): TranslationResult {
        return analyzeWithResult(listOf(), jsonCompilationDatabase.parentFile.toPath(), usePasses) {
            val db = CompilationDatabase.fromFile(jsonCompilationDatabase)
            if (db.isNotEmpty()) {
                it.useCompilationDatabase(db)
                it.softwareComponents(db.components)
                configModifier?.accept(it)
            }
            configModifier?.accept(it)
        }
    }

    @Throws(IllegalAccessException::class)
    fun disableTypeManagerCleanup() {
        val spy = Mockito.spy(TypeManager.getInstance())
        Mockito.doNothing().`when`(spy).cleanup()
        FieldUtils.writeStaticField(TypeManager::class.java, "instance", spy, true)
    }

    /**
     * Returns the first element of the specified Class-type `specificClass` that has the name
     * `name` in the list `listOfNodes`.
     *
     * @param <S> Some class that extends [Node]. </S>
     */
    fun <S : Node?> getOfTypeWithName(
        listOfNodes: List<Node?>?,
        specificClass: Class<S>?,
        name: String
    ): S? {
        val listOfNodesWithName =
            Util.filterCast(listOfNodes, specificClass)
                .stream()
                .filter { s: S -> s!!.name == name }
                .collect(Collectors.toList())
        return if (listOfNodesWithName.isEmpty()) {
            null
        } else listOfNodesWithName[0]
        // Here we return the first node, if there are more nodes
    }

    /**
     * Returns the first element of the specified Class-type {code specifiedClass} that has the name
     * `name` in the list of nodes that are subnodes of the AST-root node `root`.
     *
     * @param <S> Some class that extends [Node]. </S>
     */
    fun <S : Node?> getSubnodeOfTypeWithName(
        root: Node?,
        specificClass: Class<S>?,
        name: String
    ): S? {
        return getOfTypeWithName(SubgraphWalker.flattenAST(root), specificClass, name)
    }

    /**
     * Given a root node in the AST graph, all AST children of the node are filtered for a specific
     * CPG Node type and returned.
     *
     * @param node root of the searched AST subtree
     * @param <S> Type variable that allows returning a list of specific type
     * @return a List of searched types </S>
     */
    inline fun <reified S : Node> flattenIsInstance(node: Node): List<S> {
        return SubgraphWalker.flattenAST(node).filterIsInstance<S>()
    }

    /** Similar to [TestUtils.flattenIsInstance] but working in a list of nodes. */
    inline fun <reified S : Node> flattenListIsInstance(
        roots: Collection<Node>,
    ): List<S> {
        return roots
            .stream()
            .map { flattenIsInstance<S>(it) }
            .flatMap { it.stream() }
            .filter(Util.distinctByIdentity())
            .collect(Collectors.toList())
    }

    /**
     * Compare the given parameter `toCompare` to the start- or end-line of the given node. If the
     * node has no location `false` is returned. `startLine` is used to specify if the start-line or
     * end-line of a location is supposed to be used.
     *
     * @param n
     * @param startLine
     * @param toCompare
     * @return
     */
    fun compareLineFromLocationIfExists(n: Node, startLine: Boolean, toCompare: Int): Boolean {
        val loc = n.location ?: return false
        return if (startLine) {
            loc.region.startLine == toCompare
        } else {
            loc.region.endLine == toCompare
        }
    }

    /**
     * Asserts, that the expression given in [expression] refers to the expected declaration [b].
     */
    fun assertRefersTo(expression: Expression?, b: Declaration?) {
        if (expression is DeclaredReferenceExpression) {
            assertEquals(b, (expression as DeclaredReferenceExpression?)?.refersTo)
        } else {
            fail("not a reference")
        }
    }

    /**
     * Asserts, that the call expression given in [call] refers to the expected function declaration
     * [func].
     */
    fun assertInvokes(call: CallExpression, func: FunctionDeclaration?) {
        assertContains(call.invokes, func)
    }
}