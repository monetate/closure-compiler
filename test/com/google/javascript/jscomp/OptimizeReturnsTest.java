/*
 * Copyright 2009 The Closure Compiler Authors.
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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests OptimizeReturns */
@RunWith(JUnit4.class)
public final class OptimizeReturnsTest extends CompilerTestCase {
  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return new OptimizeReturns(compiler);
  }

  private static final String EXTERNAL_SYMBOLS =
      """
      var extern;
      extern.externalMethod
      """;

  public OptimizeReturnsTest() {
    super(DEFAULT_EXTERNS + EXTERNAL_SYMBOLS);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    enableNormalize(); // Required for `OptimizeCalls`.
    // TODO(bradfordcsmith): Stop normalizing the expected output or document why it is necessary.
    enableNormalizeExpectedOutput();
    enableGatherExternProperties();
  }

  @Override
  protected int getNumRepetitions() {
    // run pass once.
    return 1;
  }

  @Test
  public void nullishCoalesceReturnRemoved() {
    test(
        "var f = (function() {return 1}) ?? (function() {return 2}); f();",
        "var f = function() { return; } ?? function() { return; }; f();");
  }

  @Test
  public void testNoRewriteUsedResult1() {
    String source =
        """
        function a(){return 1}
        var x = a()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteUsedResult2() {
    String source =
        """
        var a = function(){return 1}
        a(); var b = a()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteUsedClassMethodResult1() {
    String source =
        """
        class C { method() {return 1} }
        var x = new C().method()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteUnsedObjectMethodResult1() {
    String source =
        """
        var o = { method() {return 1} }
        o.method()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredArray1() {
    String source =
        """
        var x = function() { return 1; };
        [x] = []
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredArray2() {
    String source =
        """
        var x = function() { return 1; };
        [x = function() {}] = []
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredArray3() {
    String source =
        """
        class C { method() { return 1 }}
        [x.method] = []
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredArray4() {
    String source =
        """
        class C { method() { return 1 }}
        [x.method] = []
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredObject1() {
    String source =
        """
        var x = function() { return 1; };
        ({a:x} = {})
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredObject2() {
    String source =
        """
        var x = function() { return 1; };
        ({a:x = function() {}} = {})
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredObject3() {
    String source =
        """
        class C { method() { return 1 }}
        ({a:x.method} = {})
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteDestructuredObject4() {
    String source =
        """
        class C { method() { return 1 }}
        ({a:x.method = function() {}} = {})
        x()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteTagged1() {
    // TODO(johnlenz): support this. Unused return can be removed.
    String source =
        """
        var f = function() { return 1; };
        f`tagged`
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteTagged2() {
    // Tagged use prevents optimizations
    String source =
        """
        var f = function() { return 1; };
        var x = f`tagged`
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteTagged3() {
    // Tagged use is not ignored.
    String source =
        """
        var f = function() { return 1; };
        var x = f`tagged`
        f()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteConstructorProp() {
    String source =
        """
        class C { constructor() { return 1 } }
        x.constructor()
        """;
    testSame(source);
  }

  @Test
  public void testRewriteUnusedResult1() {
    String source =
        """
        function a(){return 1}
        a()
        """;
    String expected =
        """
        function a(){return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult2() {
    String source =
        """
        var a; a = function(){return 1}
        a()
        """;
    String expected =
        """
        var a; a = function(){return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult3() {
    String source =
        """
        var a = function(){return 1}
        a()
        """;
    String expected =
        """
        var a = function(){return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult4a() {
    String source =
        """
        var a = function(){return a()}
        a()
        """;
    testSame(source);
  }

  @Test
  public void testRewriteUnusedResult4b() {
    String source =
        """
        var a = function b(){return b()}
        a()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteWhenAliasedDuringAssignment() {
    String source =
        """
        var a, b;
        a = b = function (){return 1}
        use(a()); // result used
        b()
        """; // result unused
    testSame(source);
  }

  @Test
  public void testRewriteUnusedResult4c() {
    String source =
        """
        function a(){return a()}
        a()
        """;
    testSame(source);
  }

  @Test
  public void testRewriteUnusedResult5() {
    String source =
        """
        function a(){}
        a.prototype.foo = function(args) {return args};
        var o = new a;
        o.foo()
        """;
    String expected =
        """
        function a(){}
        a.prototype.foo = function(args) {args;return};
        var o = new a;
        o.foo()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult6() {
    String source =
        """
        function a(){return (g = 1)}
        a()
        """;
    String expected =
        """
        function a(){g = 1;return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult7a() {
    String source =
        """
        function a() { return 1 }
        function b() { return a() }
        function c() { return b() }
        c();
        """;

    String expected =
        """
        function a() { return 1 }
        function b() { return a() }
        function c() { b(); return }
        c();
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult7b() {
    String source =
        """
        c();
        function c() { return b() }
        function b() { return a() }
        function a() { return 1 }
        """;

    // Iteration 1.
    String expected =
        """
        c();
        function c() { b(); return }
        function b() { return a() }
        function a() { return 1 }
        """;
    test(source, expected);

    // Iteration 2.
    source = expected;
    expected =
        """
        c();
        function c() { b(); return }
        function b() { a(); return }
        function a() { return 1 }
        """;
    test(source, expected);

    // Iteration 3.
    source = expected;
    expected =
        """
        c();
        function c() { b(); return }
        function b() { a(); return }
        function a() { return }
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResult8() {
    String source =
        """
        function a() { return c() }
        function b() { return a() }
        function c() { return b() }
        c();
        """;
    testSame(source);
  }

  @Test
  public void testRewriteUnusedResult9() {
    // Proves that the deleted function scope is reported.
    String source =
        """
        function a(){return function() {};}
        a()
        """;
    String expected =
        """
        function a(){(function() {}); return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUsedResult10() {
    String source =
        """
        class C { method() {return 1} }
        new C().method()
        """;
    String expected =
        """
        class C { method() {return} }
        new C().method()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedTemplateLitResult() {
    // Proves that the deleted function scope is reported.
    String source =
        """
        function a(){ return `template`; }
        a()
        """;
    String expected =
        """
        function a(){ return; }
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedAsyncResult1() {
    // Async function returns can be dropped if no-one waits on the returned
    // promise.
    String source =
        """
        async function a(){return promise}
        a()
        """;
    String expected =
        """
        async function a(){promise; return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedGeneratorResult1() {
    // Generator function returns can be dropped if no-one uses the returned
    // iterator.
    String source =
        """
        function *a(){return value}
        a()
        """;
    String expected =
        """
        function *a(){value; return}
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testNoRewriteObjLit1() {
    String source =
        """
        var a = {b:function(){return 1;}}
        for(c in a) (a[c])();
        a.b()
        """;
    testSame(source);
  }

  @Test
  public void testNoRewriteObjLit2() {
    String source =
        """
        var a = {b:function fn(){return 1;}}
        for(c in a) (a[c])();
        a.b()
        """;
    testSame(source);

    String sourceOptChain =
        """
        var a = {b:function fn(){return 1;}}
        for(c in a) (a[c])?.();
        a.b?.()
        """;
    testSame(sourceOptChain);
  }

  @Test
  public void testNoRewriteArrLit() {
    String source =
        """
        var a = [function(){return 1;}]
        (a[0])();
        """;
    testSame(source);

    String sourceOptChain =
        """
        var a = [function(){return 1;}]
        (a[0])?.();
        """;
    testSame(sourceOptChain);
  }

  @Test
  public void testPrototypeMethod1() {
    String source =
        """
        function c(){}
        c.prototype.a = function(){return 1}
        var x = new c;
        x.a()
        """;
    String result =
        """
        function c(){}
        c.prototype.a = function(){return}
        var x = new c;
        x.a()
        """;
    test(source, result);
  }

  @Test
  public void testPrototypeMethod2() {
    String source =
        """
        function c(){}
        c.prototype.a = function(){return 1}
        goog.reflect.object({a: 'v'})
        var x = new c;
        x.a()
        """;
    testSame(source);
  }

  @Test
  public void testPrototypeMethod3() {
    String source =
        """
        function c(){}
        c.prototype.a = function(){return 1}
        var x = new c;
        for(var key in goog.reflect.object({a: 'v'})){ x[key](); }
        x.a()
        """;
    testSame(source);
  }

  @Test
  public void testPrototypeMethod4() {
    String source =
        """
        function c(){}
        c.prototype.a = function(){return 1}
        var x = new c;
        for(var key in goog.reflect.object({a: 'v'})){ x[key](); }
        """;
    testSame(source);
  }

  @Test
  public void testCallOrApply() {
    // TODO(johnlenz): Add support for .apply
    test("function a() {return 1}; a.call(new foo);", "function a() {return  }; a.call(new foo);");
    testSame("function a() {return 1}; a.apply(new foo);");
  }

  @Test
  public void testCallOrApply_optChain() {
    test(
        "function a() {return 1}; a?.call(new foo);", //
        "function a() {return  }; a?.call(new foo);");
    testSame("function a() {return 1}; a?.apply(new foo);");
  }

  @Test
  public void testRewriteUseSiteRemoval() {
    String source =
        """
        function a() { return {"_id" : 1} }
        a();
        """;
    String expected =
        """
        function a() { ({"_id" : 1}); return }
        a();
        """;
    test(source, expected);
  }

  @Test
  public void testUnknownDefinitionAllowRemoval() {
    // TODO(johnlenz): allow this to be optimized.
    testSame(
        """
        let x = functionFactory();
        x(1, 2);
        x = function(a,b) { return b; }
        """);
  }

  @Test
  public void testReturnNotRemovedFromRecursiveNamedFunctionExpression() {
    testSame(
        """
        let x = function innerName(n) {
          if (n < 1) {
            return 0
          } else {
            return innerName(n - 1) + n;
          }

        }
        x(3);
        """);
  }

  // optional chaining version of the above test
  @Test
  public void testReturnNotRemovedFromRecursiveNamedFunctionExpression_optChain() {
    testSame(
        """
        let x = function innerName(n) {
          if (n < 1) {
            return 0
          } else {
            return innerName(n - 1) + n;
          }

        }
        x?.(3);
        """);
  }

  @Test
  public void testRewriteUnusedResultWithSafeReference1() {
    String source =
        """
        function a(){return 1}
        typeof a
        a()
        """;
    String expected =
        """
        function a(){return}
        typeof a
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResultWithSafeReference2() {
    String source =
        """
        function a(){return 1}
        x instanceof a
        a instanceof x
        a()
        """;
    String expected =
        """
        function a(){return}
        x instanceof a
        a instanceof x
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResultWithSafeReference3() {
    String source =
        """
        function a(){return 1}
        x in a
        a in x
        a()
        """;
    String expected =
        """
        function a(){return}
        x in a
        a in x
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResultWithSafeReference4() {
    String source =
        """
        function a(){return 1}
        a.x
        a['x']
        a()
        """;
    String expected =
        """
        function a(){return}
        a.x
        a['x']
        a()
        """;
    test(source, expected);
  }

  @Test
  public void testRewriteUnusedResultWithSafeReference5() {
    String source =
        """
        function a(){return 1}
        for (x in a) {}
        for (x of a) {}
        a()
        """;
    String expected =
        """
        function a(){return}
        for (x in a) {}
        for (x of a) {}
        a()
        """;
    test(source, expected);

    // optional versions
    String sourceOptChainCall =
        """
        function a(){return 1}
        for (x in a) {}
        for (x of a) {}
        a?.()
        """;
    String expectedOptChainCall =
        """
        function a(){return}
        for (x in a) {}
        for (x of a) {}
        a?.()
        """;
    test(sourceOptChainCall, expectedOptChainCall);
  }

  @Test
  public void testNoRewriteUnusedResultWithUnsafeReference1() {
    // call to 'a.x' escapes 'a' as 'this'
    String source =
        """
        function a(){return 1}
        a.x()
        a()
        """;
    testSame(source);

    // call to 'a?.x' escapes 'a' as 'this'
    String sourceOptChainCall =
        """
        function a(){return 1}
        a?.x()
        a()
        """;
    testSame(sourceOptChainCall);
  }

  @Test
  public void testNoRewriteUnusedResultWithUnsafeReference2() {
    // call to 'a[x]' escapes 'a' as 'this'
    String source =
        """
        function a(){return 1}
        a['x']()
        a()
        """;
    testSame(source);

    // call to 'a?.[x]' escapes 'a' as 'this'
    String sourceOptChainCall =
        """
        function a(){return 1}
        a?.['x']()
        a()
        """;
    testSame(sourceOptChainCall);
  }

  @Test
  public void testNoRewriteUnusedResultWithUnsafeReference4() {
    // call to 'a' is assigned an unknown value
    // TODO(johnlenz): optimize this.
    String source =
        """
        function a(){return 1}
        for (a of x) {}
        a()
        """;
    testSame(source);
  }
}
