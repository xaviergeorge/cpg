/*
 * Copyright (c) 2021, Fraunhofer AISEC. All rights reserved.
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
package de.fraunhofer.aisec.cpg.frontends.cpp2

import de.fraunhofer.aisec.cpg.TranslationConfiguration
import de.fraunhofer.aisec.cpg.frontends.LanguageFrontend
import de.fraunhofer.aisec.cpg.graph.Annotation
import de.fraunhofer.aisec.cpg.graph.AnnotationMember
import de.fraunhofer.aisec.cpg.graph.NodeBuilder
import de.fraunhofer.aisec.cpg.graph.NodeBuilder.newTranslationUnitDeclaration
import de.fraunhofer.aisec.cpg.graph.TypeManager
import de.fraunhofer.aisec.cpg.graph.declarations.RecordDeclaration
import de.fraunhofer.aisec.cpg.graph.declarations.TranslationUnitDeclaration
import de.fraunhofer.aisec.cpg.graph.types.Type
import de.fraunhofer.aisec.cpg.graph.types.TypeParser
import de.fraunhofer.aisec.cpg.graph.types.UnknownType
import de.fraunhofer.aisec.cpg.passes.scopes.ScopeManager
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation
import de.fraunhofer.aisec.cpg.sarif.Region
import io.github.oxisto.kotlintree.jvm.*
import java.io.File
import java.util.ArrayList

/**
 * An (experimental) language frontend for the [TypeManager.Language.CXX] language based on
 * [Tree-sitter](https://tree-sitter.github.io).
 */
class CXXLanguageFrontend2(config: TranslationConfiguration, scopeManager: ScopeManager?) :
    LanguageFrontend(config, scopeManager, "::") {

    lateinit var input: String
    lateinit var currentFile: File

    var expressionHandler = ExpressionHandler(this)
    var statementHandler = StatementHandler(this)
    var declarationHandler = DeclarationHandler(this)

    override fun parse(file: File): TranslationUnitDeclaration {
        TypeManager.getInstance().setLanguageFrontend(this)

        val parser = Parser()
        parser.language = TreeSitterCpp.INSTANCE.tree_sitter_cpp()

        input = file.readText()
        currentFile = file

        val tree = parser.parseString(null, input)
        val root = tree.rootNode

        println(root.string)

        assert(root.type == "translation_unit")

        return handleTranslationUnit(root, file.absolutePath)
    }

    fun handleTranslationUnit(node: Node, path: String): TranslationUnitDeclaration {
        val tu = newTranslationUnitDeclaration(path, getCodeFromRawNode(node))

        scopeManager.resetToGlobal(tu)

        // loop through children
        for (childNode in node) {
            // skip anonymous nodes
            if (!childNode.isNamed) {
                continue
            }

            val declaration = declarationHandler.handle(childNode)

            scopeManager.addDeclaration(declaration)
        }

        return tu
    }

    fun handleTypeWithQualifier(node: Node): Type {
        var qualifier: String = ""
        for (i in 0 until node.namedChildCount) {
            if (!node.namedChild(i).isNull && node.namedChild(i).type.equals("type_qualifier")) {
                qualifier = getCodeFromRawNode(node.namedChild(i))!! + " "
                break
            }
        }

        return handleType(node.childByFieldName("type"), qualifier)
    }

    fun handleType(node: Node, qualifier: String = ""): Type {
        // make sure this node is really valid
        if (node.isNull) {
            return UnknownType.getUnknownType()
        }

        return when (node.type) {
            "primitive_type" ->
                getCodeFromRawNode(node)?.let { TypeParser.createFrom(qualifier + it, false) }
                    ?: UnknownType.getUnknownType()
            "type_identifier" ->
                getCodeFromRawNode(node)?.let { TypeParser.createFrom(qualifier + it, false) }
                    ?: UnknownType.getUnknownType()
            "scoped_type_identifier" ->
                getCodeFromRawNode(node)?.let { TypeParser.createFrom(qualifier + it, false) }
                    ?: UnknownType.getUnknownType()
            "class_specifier" -> handleRecordSpecifier(node)
            "struct_specifier" -> handleRecordSpecifier(node)
            "auto" -> UnknownType.getUnknownType()
            "type_descriptor" ->
                getCodeFromRawNode(node)?.let { TypeParser.createFrom(qualifier + it, false) }
                    ?: UnknownType.getUnknownType()
            "template_type" ->
                getCodeFromRawNode(node)?.let { TypeParser.createFrom(qualifier + it, false) }
                    ?: UnknownType.getUnknownType()
            else -> {
                log.error(
                    "Not handling type of type {} yet: {}",
                    node.type,
                    getCodeFromRawNode(node)
                )
                return UnknownType.getUnknownType()
            }
        }
    }

    private fun handleRecordSpecifier(node: Node): Type {
        val recordDeclaration = declarationHandler.handle(node) as? RecordDeclaration

        return recordDeclaration?.toType() ?: UnknownType.getUnknownType()
    }

    fun processAttributes(cpgNode: de.fraunhofer.aisec.cpg.graph.Node, attributeNode: Node) {
        if (config.processAnnotations) {
            for (i in 0 until attributeNode.namedChildCount) {
                if (!attributeNode.namedChild(i).isNull &&
                        attributeNode.namedChild(i).type.equals("attribute")
                ) {
                    // set attributes
                    cpgNode.addAnnotation(handleAttributes(attributeNode.namedChild(i)))
                }
            }
        }
    }

    private fun handleAttributes(attribute: Node): Annotation {
        val attributeNode = attribute.namedChild(0)
        val argumentList = attributeNode.namedChild(1)
        val annotation =
            NodeBuilder.newAnnotation(
                getCodeFromRawNode(attributeNode.namedChild(0)),
                getCodeFromRawNode(attributeNode)
            )

        val annotationMembers: MutableList<AnnotationMember> = ArrayList()

        if (argumentList.type.equals("argument_list")) {
            for (i in 0 until argumentList.namedChildCount) {
                val expression = expressionHandler.handle(argumentList.namedChild(i))
                annotationMembers.add(
                    NodeBuilder.newAnnotationMember(
                        "",
                        expression,
                        getCodeFromRawNode(argumentList.namedChild(i))
                    )
                )
            }
        }

        annotation.members = annotationMembers

        return annotation
    }

    override fun <T : Any?> getCodeFromRawNode(astNode: T): String? {
        if (astNode is Node && !astNode.isNull) {
            val start = astNode.startByte
            val end = astNode.endByte

            return input.substring(start, end)
        }

        return null
    }

    override fun <T : Any?> getLocationFromRawNode(astNode: T): PhysicalLocation? {
        if (astNode is Node) {
            val start = astNode.startPoint
            val end = astNode.endPoint

            // In tree-sitter lines and columns start counting at 0. This is counter-intuitive to
            // the locations shown by IDEs
            return PhysicalLocation(
                currentFile.toURI(),
                Region(start.row + 1, start.column + 1, end.row + 1, end.column + 1)
            )
        }

        return null
    }

    override fun <S : Any?, T : Any?> setComment(s: S, ctx: T) {}
}