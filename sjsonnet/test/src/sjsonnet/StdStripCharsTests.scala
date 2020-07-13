package sjsonnet

import utest._

object StdStripCharsTests extends TestSuite {
  def eval(s: String) = {
    new Interpreter(
      SjsonnetMain.createParseCache(),
      Map(),
      Map(),
      DummyPath(),
      (_, _) => None
    ).interpret(s, DummyPath("(memory)")) match {
      case Right(x) => x
      case Left(e) => throw new Exception(e)
    }
  }

  def tests = Tests {
    test("stdRStripChars") {
      eval("std.rstripChars(\" test test test \", \" \")").toString() ==> """" test test test""""
      eval("std.rstripChars(\"aaabbbbcccc\", \"ac\")").toString() ==> """"aaabbbb""""
      eval("std.rstripChars(\"cacabbbbaacc\", \"ac\")").toString() ==> """"cacabbbb""""
    }
    test("stdLStripChars") {
      eval("std.lstripChars(\" test test test \", \" \")").toString() ==> """"test test test """"
      eval("std.lstripChars(\"aaabbbbcccc\", \"ac\")").toString() ==> """"bbbbcccc""""
      eval("std.lstripChars(\"cacabbbbaacc\", \"ac\")").toString() ==> """"bbbbaacc""""
    }
    test("stdStripChars") {
      eval("std.stripChars(\" test test test \", \" \")").toString() ==> """"test test test""""
      eval("std.stripChars(\"aaabbbbcccc\", \"ac\")").toString() ==> """"bbbb""""
      eval("std.stripChars(\"cacabbbbaacc\", \"ac\")").toString() ==> """"bbbb""""
    }

  }
}
