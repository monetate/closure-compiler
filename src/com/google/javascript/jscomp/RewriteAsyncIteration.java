/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.AstFactory.type;

import com.google.javascript.jscomp.colors.StandardColors;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticScope;
import com.google.javascript.rhino.Token;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Converts async generator functions into a function returning a new $jscomp.AsyncGenWrapper around
 * the original block and awaits/yields converted to yields of ActionRecords.
 *
 * <pre>{@code
 * async function* foo() {
 *   let res = await myPromise;
 *   yield res + 1;
 * }
 * }</pre>
 *
 * <p>becomes (prefixes trimmed for clarity)
 *
 * <pre>{@code
 * function foo() {
 *   return new $jscomp.AsyncGeneratorWrapper((function*(){
 *     let res = yield new $ActionRecord($ActionEnum.AWAIT_VALUE, myPromise);
 *     yield new $ActionRecord($ActionEnum.YIELD_VALUE, res + 1);
 *   })());
 * }
 * }</pre>
 */
public final class RewriteAsyncIteration implements NodeTraversal.Callback, CompilerPass {

  private static final FeatureSet transpiledFeatures =
      FeatureSet.BARE_MINIMUM.with(Feature.ASYNC_GENERATORS, Feature.FOR_AWAIT_OF);

  static final DiagnosticType CANNOT_CONVERT_ASYNCGEN =
      DiagnosticType.error("JSC_CANNOT_CONVERT_ASYNCGEN", "Cannot convert async generator. {0}");

  private static final String ACTION_RECORD_NAME = "$jscomp.AsyncGeneratorWrapper$ActionRecord";

  private static final String ACTION_ENUM_AWAIT =
      "$jscomp.AsyncGeneratorWrapper$ActionEnum.AWAIT_VALUE";
  private static final String ACTION_ENUM_YIELD =
      "$jscomp.AsyncGeneratorWrapper$ActionEnum.YIELD_VALUE";
  private static final String ACTION_ENUM_YIELD_STAR =
      "$jscomp.AsyncGeneratorWrapper$ActionEnum.YIELD_STAR";
  
  // Variables with these names get created when rewriting for-await-of loops
  private static final String FOR_AWAIT_ITERATOR_TEMP_NAME = "$jscomp$forAwait$tempIterator";
  private static final String FOR_AWAIT_RESULT_TEMP_NAME = "$jscomp$forAwait$tempResult";
  private static final String FOR_AWAIT_ERROR_RESULT_TEMP_NAME = "$jscomp$forAwait$errResult";
  private static final String FOR_AWAIT_CATCH_PARAM_TEMP_NAME = "$jscomp$forAwait$catchErrParam";
  private static final String FOR_AWAIT_RETURN_FN_TEMP_NAME = "$jscomp$forAwait$retFn";

  private int nextForAwaitId = 0;

  private final AbstractCompiler compiler;

  private final ArrayDeque<LexicalContext> contextStack;
  private static final String THIS_VAR_NAME = "$jscomp$asyncIter$this$";
  private static final String ARGUMENTS_VAR_NAME = "$jscomp$asyncIter$arguments";
  private static final String SUPER_PROP_GETTER_PREFIX = "$jscomp$asyncIter$super$get$";
  private final AstFactory astFactory;
  private final StaticScope namespace;

  /** Tracks a function and its context of this/arguments/super, if such a context exists. */
  private static final class LexicalContext {

    // Node that creates the context
    private final Node contextRoot;
    // The current function, or null if root scope where we are not in a function.
    private final @Nullable Node function;
    // The context of the most recent definition of this/super/arguments
    private final @Nullable ThisSuperArgsContext thisSuperArgsContext;

    // Represents the global/root scope. Should only exist on the bottom of the contextStack.
    private LexicalContext(Node contextRoot) {
      this.contextRoot = checkNotNull(contextRoot);
      this.function = null;
      this.thisSuperArgsContext =
          null; // no need for global context to have a this/super/args context
    }

    /**
     * Represents the context of a function or its parameter list.
     *
     * @param parent enclosing context
     * @param contextRoot FUNCTION or PARAM_LIST node
     * @param function same as contextRoot or the FUNCTION containing the PARAM_LIST
     */
    private LexicalContext(
        LexicalContext parent, Node contextRoot, Node function, AbstractCompiler compiler) {
      checkNotNull(parent);
      checkNotNull(contextRoot);
      checkArgument(contextRoot == function || contextRoot.isParamList(), contextRoot);
      checkNotNull(function);
      checkArgument(function.isFunction(), function);
      this.contextRoot = contextRoot;
      this.function = function;

      if (function.isArrowFunction()) {
        // Use the parent context to inherit this, arguments, and super for an arrow function or its
        // parameter list.
        this.thisSuperArgsContext = parent.thisSuperArgsContext;
      } else if (contextRoot.isFunction()) {
        // Non-arrow function gets its own context defining `this`, `arguments`, and `super`.
        String newUniqueId =
            compiler
                .getUniqueIdSupplier()
                .getUniqueId(compiler.getInput(NodeUtil.getInputId(contextRoot)));
        this.thisSuperArgsContext = new ThisSuperArgsContext(this, newUniqueId);
      } else {
        // contextRoot is a parameter list.
        // Never alias `this`, `arguments`, or `super` for normal function parameter lists.
        // They are implicitly defined there.
        this.thisSuperArgsContext = null;
      }
    }

    static LexicalContext newGlobalContext(Node contextRoot) {
      return new LexicalContext(contextRoot);
    }

    static LexicalContext newContextForFunction(
        LexicalContext parent, Node function, AbstractCompiler compiler) {
      // Functions need their own context because:
      //     - async generator functions must be transpiled
      //     - non-async generator functions must NOT be transpiled
      //     - arrow functions inside of async generator functions need to have
      //       `this`, `arguments`, and `super` references aliased, including in their
      //       parameter lists
      return new LexicalContext(parent, function, function, compiler);
    }

    static LexicalContext newContextForParamList(
        LexicalContext parent, Node paramList, AbstractCompiler compiler) {
      // Parameter lists need their own context because `this`, `arguments`, and `super` must NOT be
      // aliased for non-arrow function parameter lists, even for async generator functions.
      return new LexicalContext(parent, paramList, parent.function, compiler);
    }

    Node getFunctionDeclaringThisArgsSuper() {
      return thisSuperArgsContext.ctx.function;
    }

    /** Is it necessary to replace `this`, `super`, and `arguments` with aliases in this context? */
    boolean mustReplaceThisSuperArgs() {
      return thisSuperArgsContext != null
          && getFunctionDeclaringThisArgsSuper().isAsyncGeneratorFunction();
    }
  }

  /**
   * Tracks how this/arguments/super were used in the function so declarations of replacement
   * variables can be prepended
   */
  private static final class ThisSuperArgsContext {

    /** The LexicalContext representing the function that declared this/super/args */
    private final LexicalContext ctx;

    private final Set<Node> usedSuperProperties = new LinkedHashSet<>();
    @Nullable Node thisNodeToAdd = null;
    private boolean usedArguments = false;
    // unique id to append to names in this context. This is used to ensure that names
    // in different contexts don't collide (e.g. 2 functions don't get the same `let
    // $jscomp$async$this` name declared in their bodies)
    private final String uniqueId;

    ThisSuperArgsContext(LexicalContext ctx, String uniqueId) {
      this.ctx = ctx;
      this.uniqueId = uniqueId;
    }
  }

  private RewriteAsyncIteration(
      AbstractCompiler compiler, AstFactory astFactory, StaticScope namespace) {
    this.compiler = checkNotNull(compiler);
    this.astFactory = checkNotNull(astFactory);
    this.namespace = checkNotNull(namespace);
    this.contextStack = new ArrayDeque<>();
  }

  static RewriteAsyncIteration create(AbstractCompiler compiler) {
    AstFactory astFactory = compiler.createAstFactory();
    StaticScope namespace = compiler.getTranspilationNamespace();
    return new RewriteAsyncIteration(compiler, astFactory, namespace);
  }

  @Override
  public void process(Node externs, Node root) {
    checkState(contextStack.isEmpty());
    contextStack.push(LexicalContext.newGlobalContext(root));
    TranspilationPasses.processTranspile(compiler, root, transpiledFeatures, this);
    TranspilationPasses.maybeMarkFeaturesAsTranspiledAway(compiler, root, transpiledFeatures);
    checkState(contextStack.element().function == null);
    contextStack.remove();
    checkState(contextStack.isEmpty());
  }

  @Override
  public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
    if (n.isFunction()) {
      contextStack.push(
          LexicalContext.newContextForFunction(contextStack.element(), n, this.compiler));
    } else if (n.isParamList()) {
      contextStack.push(
          LexicalContext.newContextForParamList(contextStack.element(), n, this.compiler));
    }
    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    LexicalContext ctx = contextStack.element();
    switch (n.getToken()) {
        // Async Generators (and popping contexts)
      case PARAM_LIST:
        // Done handling parameter list, so pop its context
        checkState(n.equals(ctx.contextRoot), n);
        contextStack.pop();
        break;
      case FUNCTION:
        checkState(n.equals(ctx.contextRoot));
        if (n.isAsyncGeneratorFunction()) {
          convertAsyncGenerator(n);
          prependTempVarDeclarations(ctx, t);
        }
        // Done handling function, so pop its context
        contextStack.pop();
        break;
      case AWAIT:
        checkNotNull(ctx.function);
        if (ctx.function.isAsyncGeneratorFunction()) {
          convertAwaitOfAsyncGenerator(ctx, n);
        }
        break;
      case YIELD: // Includes yield*
        checkNotNull(ctx.function);
        if (ctx.function.isAsyncGeneratorFunction()) {
          convertYieldOfAsyncGenerator(ctx, n);
        }
        break;
      case RETURN:
        checkNotNull(ctx.function);
        if (ctx.function.isAsyncGeneratorFunction()) {
          convertReturnOfAsyncGenerator(ctx, n);
        }
        break;

        // For-Await-Of loops
      case FOR_AWAIT_OF:
        checkNotNull(ctx.function);
        checkState(ctx.function.isAsyncFunction());
        replaceForAwaitOf(ctx, n);
        NodeUtil.addFeatureToScript(t.getCurrentScript(), Feature.CONST_DECLARATIONS, compiler);
        break;

        // Maintaining references to this/arguments/super
      case THIS:
        if (ctx.mustReplaceThisSuperArgs()) {
          replaceThis(ctx, n);
        }
        break;
      case NAME:
        if (ctx.mustReplaceThisSuperArgs() && n.matchesName("arguments")) {
          replaceArguments(ctx, n);
        }
        break;
      case SUPER:
        if (ctx.mustReplaceThisSuperArgs()) {
          replaceSuper(ctx, n, parent);
        }
        break;

      default:
        break;
    }
  }

  /**
   * Moves the body of an async generator function into a nested generator function and removes the
   * async and generator props from the original function.
   *
   * <pre>{@code
   * async function* foo() {
   *   bar();
   * }
   * }</pre>
   *
   * <p>becomes
   *
   * <pre>{@code
   * function foo() {
   *   return new $jscomp.AsyncGeneratorWrapper((function*(){
   *     bar();
   *   })())
   * }
   * }</pre>
   *
   * @param originalFunction the original AsyncGeneratorFunction Node to be converted.
   */
  private void convertAsyncGenerator(Node originalFunction) {
    checkNotNull(originalFunction);
    checkState(originalFunction.isAsyncGeneratorFunction());

    Node asyncGeneratorWrapperRef =
        astFactory.createQName(this.namespace, "$jscomp.AsyncGeneratorWrapper");
    Node innerFunction = astFactory.createEmptyAsyncGeneratorWrapperArgument(null);

    Node innerBlock = originalFunction.getLastChild();
    innerBlock.detach();
    innerFunction.getLastChild().replaceWith(innerBlock);

    // Body should be:
    // return new $jscomp.AsyncGeneratorWrapper((new function with original block here)());
    Node outerBlock =
        astFactory.createBlock(
            astFactory.createReturn(
                astFactory.createNewNode(
                    asyncGeneratorWrapperRef,
                    astFactory.createCall(innerFunction, type(StandardColors.GENERATOR_ID)))));
    originalFunction.addChildToBack(outerBlock);

    originalFunction.setIsAsyncFunction(false);
    originalFunction.setIsGeneratorFunction(false);
    originalFunction.srcrefTreeIfMissing(originalFunction);
    // Both the inner and original functions should be marked as changed.
    compiler.reportChangeToChangeScope(originalFunction);
    compiler.reportChangeToChangeScope(innerFunction);
  }

  /**
   * Converts an await into a yield of an ActionRecord to perform "AWAIT".
   *
   * <pre>{@code await myPromise}</pre>
   *
   * <p>becomes
   *
   * <pre>{@code yield new ActionRecord(ActionEnum.AWAIT_VALUE, myPromise)}</pre>
   *
   * @param awaitNode the original await Node to be converted
   */
  private void convertAwaitOfAsyncGenerator(LexicalContext ctx, Node awaitNode) {
    checkNotNull(awaitNode);
    checkState(awaitNode.isAwait());
    checkState(ctx != null && ctx.function != null);
    checkState(ctx.function.isAsyncGeneratorFunction());

    Node expression = awaitNode.removeFirstChild();
    checkNotNull(expression, "await needs an expression");
    Node newActionRecord =
        astFactory.createNewNode(
            astFactory.createQName(this.namespace, ACTION_RECORD_NAME),
            astFactory.createQName(this.namespace, ACTION_ENUM_AWAIT),
            expression);
    newActionRecord.srcrefTreeIfMissing(awaitNode);
    awaitNode.addChildToFront(newActionRecord);
    awaitNode.setToken(Token.YIELD);
  }

  /**
   * Converts a yield into a yield of an ActionRecord to perform "YIELD" or "YIELD_STAR".
   *
   * <pre>{@code
   * yield;
   * yield first;
   * yield* second;
   * }</pre>
   *
   * <p>becomes
   *
   * <pre>{@code
   * yield new ActionRecord(ActionEnum.YIELD_VALUE, undefined);
   * yield new ActionRecord(ActionEnum.YIELD_VALUE, first);
   * yield new ActionRecord(ActionEnum.YIELD_STAR, second);
   * }</pre>
   *
   * @param yieldNode the Node to be converted
   */
  private void convertYieldOfAsyncGenerator(LexicalContext ctx, Node yieldNode) {
    checkNotNull(yieldNode);
    checkState(yieldNode.isYield());
    checkState(ctx != null && ctx.function != null);
    checkState(ctx.function.isAsyncGeneratorFunction());

    Node expression = yieldNode.removeFirstChild();
    Node newActionRecord =
        astFactory.createNewNode(astFactory.createQName(this.namespace, ACTION_RECORD_NAME));

    if (yieldNode.isYieldAll()) {
      checkNotNull(expression);
      // yield* expression becomes new ActionRecord(YIELD_STAR, expression)
      newActionRecord.addChildToBack(
          astFactory.createQName(this.namespace, ACTION_ENUM_YIELD_STAR));
      newActionRecord.addChildToBack(expression);
    } else {
      if (expression == null) {
        expression = NodeUtil.newUndefinedNode(null);
      }
      // yield expression becomes new ActionRecord(YIELD, expression)
      newActionRecord.addChildToBack(astFactory.createQName(this.namespace, ACTION_ENUM_YIELD));
      newActionRecord.addChildToBack(expression);
    }
    
    newActionRecord.srcrefTreeIfMissing(yieldNode);
    yieldNode.addChildToFront(newActionRecord);
    yieldNode.putBooleanProp(Node.YIELD_ALL, false);
  }

  /**
   * Converts a return into a return of an ActionRecord.
   *
   * <pre>{@code
   * return;
   * return value;
   * }</pre>
   *
   * <p>becomes
   *
   * <pre>{@code
   * return new ActionRecord(ActionEnum.YIELD_VALUE, undefined);
   * return new ActionRecord(ActionEnum.YIELD_VALUE, value);
   * }</pre>
   *
   * @param returnNode the Node to be converted
   */
  private void convertReturnOfAsyncGenerator(LexicalContext ctx, Node returnNode) {
    checkNotNull(returnNode);
    checkState(returnNode.isReturn());
    checkState(ctx != null && ctx.function != null);
    checkState(ctx.function.isAsyncGeneratorFunction());
    
    Node expression = returnNode.removeFirstChild();
    Node newActionRecord =
        astFactory.createNewNode(astFactory.createQName(this.namespace, ACTION_RECORD_NAME));
    
    if (expression == null) {
      expression = NodeUtil.newUndefinedNode(null);
    }
    // return expression becomes new ActionRecord(YIELD, expression)
    newActionRecord.addChildToBack(astFactory.createQName(this.namespace, ACTION_ENUM_YIELD));
    newActionRecord.addChildToBack(expression);
    
    newActionRecord.srcrefTreeIfMissing(returnNode);
    returnNode.addChildToFront(newActionRecord);
  }

  /**
   * Rewrites for await of loop.
   *
   * <pre>{@code
   * for await (lhs of rhs) { block(); }
   * }</pre>
   *
   * <p>...becomes...
   *
   * <pre>{@code
   * var errorRes, retFn, tmpRes;
   * try {
   *   for (var tmpIterator = makeAsyncIterator(rhs);;) {
   *      tmpRes = await tmpIterator.next();
   *      if (tmpRes.done) {
   *        break;
   *      }
   *      lhs = $tmpRes.value;
   *      {
   *        block(); // Wrapped in a block in case block re-declares lhs variable.
   *      }
   *   }
   * } catch(e) {
   *   errorRes = { error: e };
   * } finally {
   *   try {
   *     if (tmpRes && !tmpRes.done && (retFn = _tmpIterator.return)) await retFn.call(tmpIterator);
   *   }
   *   finally { if (errorRes) throw errorRes.error; }
   * }
   *
   * }</pre>
   */
  private void replaceForAwaitOf(LexicalContext ctx, Node forAwaitOf) {
    int forAwaitId = nextForAwaitId++;
    String iteratorTempName = FOR_AWAIT_ITERATOR_TEMP_NAME + forAwaitId;
    String resultTempName = FOR_AWAIT_RESULT_TEMP_NAME + forAwaitId;
    String errorResultTempName = FOR_AWAIT_ERROR_RESULT_TEMP_NAME + forAwaitId;
    String catchErrorParamTempName = FOR_AWAIT_CATCH_PARAM_TEMP_NAME + forAwaitId;
    String returnFuncTempName = FOR_AWAIT_RETURN_FN_TEMP_NAME + forAwaitId;

    checkState(forAwaitOf.hasParent(), "Cannot replace parentless for-await-of");

    final Node forAwaitOfParent = forAwaitOf.getParent();
    final Node replacementPoint;
    if (forAwaitOfParent.isLabel()) {
      // If the forAwaitOf is a label's statement child, then the label must move with the for upon
      // rewriting.
      checkState(forAwaitOf.isSecondChildOf(forAwaitOfParent), forAwaitOfParent);
      replacementPoint = forAwaitOfParent;
    } else {
      replacementPoint = forAwaitOf;
    }

    Node lhs = forAwaitOf.removeFirstChild();
    Node rhs = forAwaitOf.removeFirstChild();
    Node originalBody = forAwaitOf.removeFirstChild();

    // Generate `var tmpIterator = makeAsyncIterator(rhs);`
    Node initializer =
        astFactory
            .createSingleVarNameDeclaration(
                iteratorTempName, astFactory.createJSCompMakeAsyncIteratorCall(rhs, this.namespace))
            .srcrefTreeIfMissing(rhs);

    // IIterableResult<VALUE> - it's a structural type so optimizations treat it as Object
    AstFactory.Type iterableResultType = type(StandardColors.TOP_OBJECT);

    // Create code `if (tmpRes.done) {break;}`
    Node breakIfDone =
        astFactory.createIf(
            astFactory.createGetProp(
                astFactory.createName(resultTempName, iterableResultType),
                "done",
                type(StandardColors.BOOLEAN)),
            astFactory.createBlock(astFactory.createBreak()));

    // Assignment statement to be moved from lhs into body of new for-loop
    Node lhsAssignment;
    final AstFactory.Type resultType;
    if (lhs.isValidAssignmentTarget()) {
      // In case of "for await (x of _)" just assign into the lhs.
      // Generate `lhs = $tmpRes.value;`
      resultType = type(lhs);
      lhsAssignment =
          astFactory.exprResult(
              astFactory.createAssign(
                  lhs,
                  astFactory.createGetProp(
                      astFactory.createName(resultTempName, iterableResultType),
                      "value",
                      resultType)));
    } else if (NodeUtil.isNameDeclaration(lhs)) {
      final Node declarationTarget = lhs.getFirstChild();
      if (declarationTarget.isName()) {
        // `for await (let x of _)`
        // Add a child to the `NAME` node to create `let x = res.value`
        resultType = type(declarationTarget);
        declarationTarget.addChildToBack(
            astFactory.createGetProp(
                astFactory.createName(resultTempName, iterableResultType), "value", resultType));
      } else {
        // Generate `for await (let [x, y] of _)`
        // Add a child to the DESTRUCTURING_LHS node to create `[x, y] = res.value`
        checkState(declarationTarget.isDestructuringLhs(), declarationTarget);
        Node destructuringPattern = declarationTarget.getOnlyChild();
        resultType = type(destructuringPattern);
        declarationTarget.addChildToBack(
            astFactory.createGetProp(
                astFactory.createName(resultTempName, iterableResultType), "value", resultType));
      }
      lhsAssignment = lhs;
    } else {
      throw new AssertionError("unexpected for-await-of lhs");
    }
    lhsAssignment.srcrefTreeIfMissing(lhs);

    // Generate `var errorRes;`
    Node errorResDecl =
        astFactory
            .createSingleVarNameDeclaration(errorResultTempName)
            .srcrefTreeIfMissing(forAwaitOf);

    // Generate `var tmpRes;`
    Node tempResultDecl =
        astFactory.createSingleVarNameDeclaration(resultTempName).srcrefTreeIfMissing(forAwaitOf);

    // Generate `var returnFunc;`
    Node returnFuncDecl =
        astFactory
            .createSingleVarNameDeclaration(returnFuncTempName)
            .srcrefTreeIfMissing(forAwaitOf);

    // Generate `tmpRes = await tmpIterator.next()`
    Node resultDeclaration =
        astFactory.exprResult(
            astFactory.createAssign(
                resultTempName,
                constructAwaitNextResult(ctx, iteratorTempName, resultType, iterableResultType)));

    Node newForLoop =
        astFactory.createFor(
            astFactory.createEmpty(),
            astFactory.createEmpty(),
            astFactory.createEmpty(),
            astFactory.createBlock(
                resultDeclaration, breakIfDone, lhsAssignment, ensureBlock(originalBody)));

    if (replacementPoint.isLabel()) {
      newForLoop = astFactory.createLabel(replacementPoint.getFirstChild().cloneNode(), newForLoop);
    }
    
    // Generates code `try { .. newForLoop .. }`
    Node tryNode = createOuterTry(newForLoop);
    initializer.insertBefore(newForLoop);

    // Generate code `catch(e) { errorRes = { error: e }; }`
    Node catchNode = createOuterCatch(catchErrorParamTempName, errorResultTempName);

    // Generate the finally code block.
    Node finallyNode =
        createOuterFinally(
            ctx,
            iterableResultType,
            resultType,
            resultTempName,
            returnFuncTempName,
            iteratorTempName,
            errorResultTempName);

    Node tryCatchFinally = astFactory.createTryCatchFinally(tryNode, catchNode, finallyNode);
    replacementPoint.replaceWith(tryCatchFinally);
    tryCatchFinally.srcrefTreeIfMissing(replacementPoint);
    errorResDecl.insertBefore(tryCatchFinally);
    tempResultDecl.insertBefore(tryCatchFinally);
    returnFuncDecl.insertBefore(tryCatchFinally);
    
    compiler.reportChangeToEnclosingScope(tryCatchFinally);
  }

  // Generates code `try { .. newForLoop .. }`
  private Node createOuterTry(Node newForLoop) {
    Node tryNode = astFactory.createBlock();
    tryNode.addChildToBack(newForLoop);
    return tryNode;
  }

  // Generates code `catch(e) { errorRes = { error: e }; }`
  private Node createOuterCatch(String catchErrorParamTempName, String errorResultTempName) {
    // Generate `errorRes = { error: e };`
    Node catchBodyStmt =
        astFactory.exprResult(
            astFactory.createAssign(
                errorResultTempName,
                astFactory.createObjectLit(
                    astFactory.createStringKey(
                        "error", astFactory.createNameWithUnknownType(catchErrorParamTempName)))));
    // Generate `{ errorRes = { error: e }; }`
    Node wrapperCatchBlockNode = astFactory.createBlock();
    wrapperCatchBlockNode.addChildToBack(catchBodyStmt);

    // Generate `catch(e) { errorRes = { error: e }; }`
    return astFactory.createCatch(
        astFactory.createNameWithUnknownType(catchErrorParamTempName), wrapperCatchBlockNode);
  }

  /**
   * Generates the outer finally code of the rewriting.
   *
   * <pre>{@code
   * finally {
   *   try {
   *     if (tmpRes && !tmpRes.done && (retFn = _tmpIterator.return)) await retFn.call(tmpIterator);
   *   }
   *   finally { if (errorRes) throw errorRes.error; }
   * }
   * }</pre>
   */
  private Node createOuterFinally(
      LexicalContext ctx,
      AstFactory.Type iterableResultType,
      AstFactory.Type resultType,
      String resultTempName,
      String returnFuncTempName,
      String iteratorTempName,
      String errorResultTempName) {
    Node finallyNode = astFactory.createBlock();

    // Generate `tmpRes`
    Node tmpResNameNode = astFactory.createNameWithUnknownType(resultTempName);
    Node tmpResDoneGetProp =
        astFactory.createGetProp(
            astFactory.createName(resultTempName, iterableResultType),
            "done",
            type(StandardColors.BOOLEAN));

    // Generate `tmpRes && !tmpRes.done`
    Node and = astFactory.createAnd(tmpResNameNode, astFactory.createNot(tmpResDoneGetProp));

    // Generate `(retFn = _tmpIterator.return)`
    Node assign =
        astFactory.createAssign(
            astFactory.createNameWithUnknownType(returnFuncTempName),
            astFactory.createGetProp(
                astFactory.createName(iteratorTempName, resultType),
                "return",
                type(StandardColors.UNKNOWN)));

    // Generate `(tmpRes && !tmpRes.done && (retFn = _tmpIterator.return))`
    Node ifCond = astFactory.createAnd(and, assign);
    Node awaitOrYieldStmt = null;
    if (ctx.function.isAsyncGeneratorFunction()) {
      // We are in an AsyncGenerator and must instead yield an "await" ActionRecord
      awaitOrYieldStmt =
          astFactory.exprResult(
              astFactory.createYield(
                  iterableResultType,
                  astFactory.createNewNode(
                      astFactory.createQName(this.namespace, ACTION_RECORD_NAME),
                      astFactory.createQName(this.namespace, ACTION_ENUM_AWAIT),
                      astFactory.createCall(
                          astFactory.createGetPropWithUnknownType(
                              astFactory.createName(
                                  returnFuncTempName, type(StandardColors.UNKNOWN)),
                              "call"),
                          type(StandardColors.UNKNOWN),
                          astFactory.createName(iteratorTempName, resultType)))));
    } else {
      //  Generate `await retFn.call(tmpIterator);`
      awaitOrYieldStmt =
          astFactory.exprResult(
              astFactory.createAwait(
                  iterableResultType,
                  astFactory.createCall(
                      astFactory.createGetPropWithUnknownType(
                          astFactory.createName(returnFuncTempName, type(StandardColors.UNKNOWN)),
                          "call"),
                      type(StandardColors.PROMISE_ID),
                      astFactory.createName(iteratorTempName, resultType))));
    }

    Node ifBody = astFactory.createBlock();
    ifBody.addChildToBack(awaitOrYieldStmt);

    Node ifBlock = astFactory.createIf(ifCond, ifBody);

    Node innerTryBlock = astFactory.createBlock();
    innerTryBlock.addChildToBack(ifBlock);

    //  `finally { if (errorRes) throw errorRes.error; }`
    Node innerFinallyBlock = astFactory.createBlock();

    // if (errorRes) throw errorRes.error;
    Node secondIfBody = astFactory.createBlock();
    Node throwStmt =
        astFactory.createThrow(
            astFactory.createGetPropWithUnknownType(
                astFactory.createNameWithUnknownType(errorResultTempName), "error"));
    secondIfBody.addChildToBack(throwStmt);
    Node secondIfCond = astFactory.createNameWithUnknownType(errorResultTempName);
    Node secondIfBlock = astFactory.createIf(secondIfCond, secondIfBody);
    innerFinallyBlock.addChildToBack(secondIfBlock);

    Node finallyBody = astFactory.createTryFinally(innerTryBlock, innerFinallyBlock);
    finallyNode.addChildToBack(finallyBody);
    return finallyNode;
  }

  private Node ensureBlock(Node possiblyBlock) {
    return possiblyBlock.isBlock()
        ? possiblyBlock
        : astFactory.createBlock(possiblyBlock).srcref(possiblyBlock);
  }

  private Node constructAwaitNextResult(
      LexicalContext ctx,
      String iteratorTempName,
      AstFactory.Type iteratorType,
      AstFactory.Type iterableResultType) {
    checkNotNull(ctx.function);
    Node result;

    Node iteratorTemp = astFactory.createName(iteratorTempName, iteratorType);

    if (ctx.function.isAsyncGeneratorFunction()) {
      // We are in an AsyncGenerator and must instead yield an "await" ActionRecord
      result =
          astFactory.createYield(
              iterableResultType,
              astFactory.createNewNode(
                  astFactory.createQName(this.namespace, ACTION_RECORD_NAME),
                  astFactory.createQName(this.namespace, ACTION_ENUM_AWAIT),
                  astFactory.createCallWithUnknownType(
                      astFactory.createGetPropWithUnknownType(iteratorTemp, "next"))));
    } else {
      result =
          astFactory.createAwait(
              iterableResultType,
              astFactory.createCall(
                  astFactory.createGetPropWithUnknownType(iteratorTemp, "next"),
                  type(StandardColors.PROMISE_ID)));
    }

    return result;
  }

  private void replaceThis(LexicalContext ctx, Node n) {
    checkArgument(n.isThis());
    checkArgument(ctx != null && ctx.mustReplaceThisSuperArgs());
    checkArgument(ctx.function != null, "Cannot prepend declarations to root scope");
    checkNotNull(ctx.thisSuperArgsContext);

    n.replaceWith(
        astFactory
            .createName(THIS_VAR_NAME + ctx.thisSuperArgsContext.uniqueId, type(n))
            .srcref(n));
    ctx.thisSuperArgsContext.thisNodeToAdd = astFactory.createThis(type(n));
    compiler.reportChangeToChangeScope(ctx.function);
  }

  private void replaceArguments(LexicalContext ctx, Node n) {
    checkArgument(n.isName() && "arguments".equals(n.getString()));
    checkArgument(ctx != null && ctx.mustReplaceThisSuperArgs());
    checkArgument(ctx.function != null, "Cannot prepend declarations to root scope");
    checkNotNull(ctx.thisSuperArgsContext);

    n.replaceWith(astFactory.createName(ARGUMENTS_VAR_NAME, type(n)).srcref(n));
    ctx.thisSuperArgsContext.usedArguments = true;
    compiler.reportChangeToChangeScope(ctx.function);
  }

  private void replaceSuper(LexicalContext ctx, Node n, Node parent) {
    if (!parent.isGetProp()) {
      compiler.report(
          JSError.make(
              parent,
              CANNOT_CONVERT_ASYNCGEN,
              "super only allowed with getprop (like super.foo(), not super['foo']())"));
      return;
    }
    checkArgument(n.isSuper());
    checkArgument(ctx != null && ctx.mustReplaceThisSuperArgs());
    checkArgument(ctx.function != null, "Cannot prepend declarations to root scope");
    checkNotNull(ctx.thisSuperArgsContext);

    String propertyName = parent.getString();
    String propertyReplacementNameText = SUPER_PROP_GETTER_PREFIX + propertyName;

    // super.x   =>   $super$get$x()
    Node getPropReplacement =
        astFactory.createCall(
            astFactory.createName(propertyReplacementNameText, type(StandardColors.TOP_OBJECT)),
            type(parent));
    Node grandparent = parent.getParent();
    if (grandparent.isCall() && grandparent.getFirstChild() == parent) {
      // super.x(...)   =>   super.x.call($this, ...)
      getPropReplacement = astFactory.createGetPropWithUnknownType(getPropReplacement, "call");
      ctx.thisSuperArgsContext.thisNodeToAdd =
          astFactory.createThisForEs6ClassMember(ctx.contextRoot.getParent());
      astFactory
          .createName(
              THIS_VAR_NAME + ctx.thisSuperArgsContext.uniqueId,
              type(ctx.thisSuperArgsContext.thisNodeToAdd))
          .srcref(parent)
          .insertAfter(parent);
    }
    getPropReplacement.srcrefTree(parent);
    parent.replaceWith(getPropReplacement);
    ctx.thisSuperArgsContext.usedSuperProperties.add(parent);
    compiler.reportChangeToChangeScope(ctx.function);
  }

  /**
   * Prepends this/super/argument replacement variables to the top of the context's block
   *
   * <pre>{@code
   * function() {
   *   return new AsyncGenWrapper(function*() {
   *     // code using replacements for this and super.foo
   *   }())
   * }
   * }</pre>
   *
   * will be converted to
   *
   * <pre>{@code
   * function() {
   *   const $jscomp$asyncIter$this = this;
   *   const $jscomp$asyncIter$super$get$foo = () => super.foo;
   *   return new AsyncGenWrapper(function*() {
   *     // code using replacements for this and super.foo
   *   }())
   * }
   * }</pre>
   */
  private void prependTempVarDeclarations(LexicalContext ctx, NodeTraversal t) {
    checkArgument(ctx != null);
    checkArgument(ctx.function != null, "Cannot prepend declarations to root scope");
    checkNotNull(ctx.thisSuperArgsContext);

    ThisSuperArgsContext thisSuperArgsCtx = ctx.thisSuperArgsContext;
    Node function = ctx.function;
    Node block = function.getLastChild();
    checkNotNull(block, function);
    Node prefixBlock = astFactory.createBlock(); // Temporary block to hold all declarations

    if (thisSuperArgsCtx.thisNodeToAdd != null) {
      // { // prefixBlock
      //   const $jscomp$asyncIter$this = this;
      // }
      prefixBlock.addChildToBack(
          astFactory
              .createSingleConstNameDeclaration(
                  THIS_VAR_NAME + thisSuperArgsCtx.uniqueId, thisSuperArgsCtx.thisNodeToAdd)
              .srcrefTree(block));
    }
    if (thisSuperArgsCtx.usedArguments) {
      // { // prefixBlock
      //   const $jscomp$asyncIter$this = this;
      //   const $jscomp$asyncIter$arguments = arguments;
      // }
      prefixBlock.addChildToBack(
          astFactory
              .createSingleConstNameDeclaration(
                  ARGUMENTS_VAR_NAME, astFactory.createArgumentsReference())
              .srcrefTree(block));
    }
    for (Node replacedMethodReference : thisSuperArgsCtx.usedSuperProperties) {
      prefixBlock.addChildToBack(createSuperMethodReferenceGetter(replacedMethodReference, t));
    }
    prefixBlock.srcrefTreeIfMissing(block);
    // Pulls all declarations out of prefixBlock and prepends in block
    // block: {
    //   // declarations
    //   // code using this/super/args
    // }
    block.addChildrenToFront(prefixBlock.removeChildren());

    if (thisSuperArgsCtx.thisNodeToAdd != null
        || thisSuperArgsCtx.usedArguments
        || !thisSuperArgsCtx.usedSuperProperties.isEmpty()) {
      compiler.reportChangeToChangeScope(function);
      NodeUtil.addFeatureToScript(t.getCurrentScript(), Feature.CONST_DECLARATIONS, compiler);
    }
  }

  private Node createSuperMethodReferenceGetter(Node replacedMethodReference, NodeTraversal t) {
    // const super$get$x = () => { return super.x; };
    AstFactory.Type typeOfSuper = type(replacedMethodReference.getFirstChild());
    Node superReference = astFactory.createSuper(typeOfSuper);
    String replacedMethodName = replacedMethodReference.getString();
    Node arrowFunction =
        astFactory.createZeroArgArrowFunctionForExpression(
            astFactory.createBlock(
                astFactory.createReturn(
                    astFactory.createGetProp(
                        superReference, replacedMethodName, type(replacedMethodReference)))));
    compiler.reportChangeToChangeScope(arrowFunction);
    NodeUtil.addFeatureToScript(t.getCurrentScript(), Feature.ARROW_FUNCTIONS, compiler);
    String superReplacementName = SUPER_PROP_GETTER_PREFIX + replacedMethodName;
    return astFactory.createSingleConstNameDeclaration(superReplacementName, arrowFunction);
  }
}
