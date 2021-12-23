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

import de.fraunhofer.aisec.cpg.frontends.Handler
import de.fraunhofer.aisec.cpg.graph.NodeBuilder
import de.fraunhofer.aisec.cpg.graph.NodeBuilder.newDeclarationStatement
import de.fraunhofer.aisec.cpg.graph.NodeBuilder.newReturnStatement
import de.fraunhofer.aisec.cpg.graph.statements.Statement
import de.fraunhofer.aisec.cpg.graph.types.ObjectType
import io.github.oxisto.kotlintree.jvm.*

class StatementHandler(lang: CXXLanguageFrontend2) :
    Handler<Statement, Node, CXXLanguageFrontend2>(::Statement, lang) {
    init {
        map.put(Node::class.java, ::handleStatement)
    }

    private fun handleStatement(node: Node): Statement {
        return when (val type = node.type) {
            "compound_statement" -> handleCompoundStatement(node)
            "declaration" -> handleDeclarationStatement(node)
            "expression_statement" -> handleExpressionStatement(node)
            "return_statement" -> handleReturnStatement(node)
            else -> {
                log.error("Not handling statement of type {} yet", type)
                configConstructor.get()
            }
        }
    }

    private fun handleReturnStatement(node: Node): Statement {
        val returnStatement = newReturnStatement(lang.getCodeFromRawNode(node))

        if (node.childCount > 0) {
            val child = node.namedChild(0)
            val expression = lang.expressionHandler.handle(child)

            returnStatement.returnValue = expression
        }

        return returnStatement
    }

    private fun handleExpressionStatement(node: Node): Statement {
        // forward the first (and only child) to the expression handler
        return lang.expressionHandler.handle(0 ofNamed node)
    }

    private fun handleDeclarationStatement(node: Node): Statement {
        val stmt = newDeclarationStatement(lang.getCodeFromRawNode(node))

        var type = lang.handleType("type" of node)

        // if the type also declared something, we add it to the declaration statement
        (type as? ObjectType)?.recordDeclaration?.let {
            // lang.scopeManager.addDeclaration(it)
            stmt.addToPropertyEdgeDeclaration(it)
        }

        var declarator = "declarator" of node
        // loop through the declarators
        do {
            val declaration =
                NodeBuilder.newVariableDeclaration("", type, lang.getCodeFromRawNode(node), false)

            lang.declarationHandler.processDeclarator(declarator, declaration)

            // update the type for the rest of the declarations
            type = declaration.type

            lang.scopeManager.addDeclaration(declaration)
            stmt.addToPropertyEdgeDeclaration(declaration)
            declarator = declarator.nextNamedSibling
        } while (!declarator.isNull)

        return stmt
    }

    private fun handleCompoundStatement(node: Node): Statement {
        val compoundStatement = NodeBuilder.newCompoundStatement(lang.getCodeFromRawNode(node))

        lang.scopeManager.enterScope(compoundStatement)

        for (i in 0 until node.namedChildCount) {
            val statement = handleStatement(i ofNamed node)

            compoundStatement.addStatement(statement)
        }

        lang.scopeManager.leaveScope(compoundStatement)

        return compoundStatement
    }
}