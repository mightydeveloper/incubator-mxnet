/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mxnet

import org.apache.mxnet.init.Base.{CPtrAddress, RefInt, RefLong, RefString, _LIB}
import org.apache.mxnet.utils.CToScalaUtils

import scala.collection.mutable.ListBuffer
import scala.reflect.macros.blackbox

private[mxnet] abstract class GeneratorBase {

  case class Arg(argName: String, argType: String, argDesc: String, isOptional: Boolean) {
    /**
      * Filter the arg name with the Scala keyword that are not allow to use as arg name,
      * such as var and type listed in here. This is due to the diff between C and Scala
      * @return argname that works in Scala
      */
    def safeArgName: String = argName match {
      case "var" => "vari"
      case "type" => "typeOf"
      case _ => argName
    }
  }

  case class Func(name: String, desc: String, listOfArgs: List[Arg], returnType: String)

  /**
    * Non Type-safe function generation method
    * This method will filter all "_" functions
    * @param isSymbol Check if generate the Symbol method
    * @param isContrib Check if generate the contrib method
    * @param isJava Check if generate Corresponding Java method
    * @return List of functions
    */
  def functionsToGenerate(isSymbol: Boolean, isContrib: Boolean,
                          isJava: Boolean = false): List[Func] = {
    val l = getBackEndFunctions(isSymbol, isJava)
    if (isContrib) {
      l.filter(func => func.name.startsWith("_contrib_") || !func.name.startsWith("_"))
    } else {
      l.filterNot(_.name.startsWith("_"))
    }
  }

  /**
    * Filter the operators to generate in the type-safe Symbol.api and NDArray.api
    * @param isSymbol Check if generate the Symbol method
    * @param isContrib Check if generate the contrib method
    * @return List of functions
    */
  protected def typeSafeFunctionsToGenerate(isSymbol: Boolean, isContrib: Boolean): List[Func] = {
    // Operators that should not be generated
    val notGenerated = Set("Custom")

    val l = getBackEndFunctions(isSymbol)
    val res = if (isContrib) {
      l.filter(func => func.name.startsWith("_contrib_") || !func.name.startsWith("_"))
    } else {
      l.filterNot(_.name.startsWith("_"))
    }
    res.filterNot(ele => notGenerated.contains(ele.name))
  }

  /**
    * Extract and format the functions obtained from C API
    * @param isSymbol Check if generate for Symbol
    * @param isJava Check if extracting in Java format
    * @return List of functions
    */
  protected def getBackEndFunctions(isSymbol: Boolean, isJava: Boolean = false): List[Func] = {
    val opNames = ListBuffer.empty[String]
    _LIB.mxListAllOpNames(opNames)
    opNames.map(opName => {
      val opHandle = new RefLong
      _LIB.nnGetOpHandle(opName, opHandle)
      makeAtomicFunction(opHandle.value, opName, isSymbol, isJava)
    }).toList
  }

  private def makeAtomicFunction(handle: CPtrAddress, aliasName: String,
                                 isSymbol: Boolean, isJava: Boolean): Func = {
    val name = new RefString
    val desc = new RefString
    val keyVarNumArgs = new RefString
    val numArgs = new RefInt
    val argNames = ListBuffer.empty[String]
    val argTypes = ListBuffer.empty[String]
    val argDescs = ListBuffer.empty[String]

    _LIB.mxSymbolGetAtomicSymbolInfo(
      handle, name, desc, numArgs, argNames, argTypes, argDescs, keyVarNumArgs)
    val extraDoc: String = if (keyVarNumArgs.value != null && keyVarNumArgs.value.length > 0) {
      s"This function support variable length of positional input (${keyVarNumArgs.value})."
    } else {
      ""
    }

    val argList = argNames zip argTypes zip argDescs map { case ((argName, argType), argDesc) =>
      val family = if (isJava) "org.apache.mxnet.javaapi.NDArray"
      else if (isSymbol) "org.apache.mxnet.Symbol"
      else "org.apache.mxnet.NDArray"
      val typeAndOption =
        CToScalaUtils.argumentCleaner(argName, argType, family, isJava)
      Arg(argName, typeAndOption._1, argDesc, typeAndOption._2)
    }
    val returnType =
      if (isJava) "Array[org.apache.mxnet.javaapi.NDArray]"
      else if (isSymbol) "org.apache.mxnet.Symbol"
      else "org.apache.mxnet.NDArrayFuncReturn"
    Func(aliasName, desc.value, argList.toList, returnType)
  }

  /**
    * Generate class structure for all function APIs
    *
    * @param c Context used for generation
    * @param funcDef DefDef type of function definitions
    * @param annottees Annottees used to define Class or Module
    * @return Expr used for code generation
    */
  protected def structGeneration(c: blackbox.Context)
                                (funcDef: List[c.universe.DefDef], annottees: c.Expr[Any]*)
  : c.Expr[Nothing] = {
    import c.universe._
    val inputs = annottees.map(_.tree).toList
    // pattern match on the inputs
    val modDefs = inputs map {
      case ClassDef(mods, name, something, template) =>
        val q = template match {
          case Template(superMaybe, emptyValDef, defs) =>
            Template(superMaybe, emptyValDef, defs ++ funcDef)
          case ex =>
            throw new IllegalArgumentException(s"Invalid template: $ex")
        }
        ClassDef(mods, name, something, q)
      case ModuleDef(mods, name, template) =>
        val q = template match {
          case Template(superMaybe, emptyValDef, defs) =>
            Template(superMaybe, emptyValDef, defs ++ funcDef)
          case ex =>
            throw new IllegalArgumentException(s"Invalid template: $ex")
        }
        ModuleDef(mods, name, q)
      case ex =>
        throw new IllegalArgumentException(s"Invalid macro input: $ex")
    }
    // wrap the result up in an Expr, and return it
    val result = c.Expr(Block(modDefs, Literal(Constant(()))))
    result
  }

  /**
    * Build function argument definition, with optionality, and safe names
    * @param func Functions
    * @return List of string representing the functions interface
    */
  protected def typedFunctionCommonArgDef(func: Func): List[String] = {
    func.listOfArgs.map(arg =>
      if (arg.isOptional) {
        // let's avoid a stupid Option[Array[...]]
        if (arg.argType.startsWith("Array[")) {
          s"${arg.safeArgName} : ${arg.argType} = Array.empty"
        } else {
          s"${arg.safeArgName} : Option[${arg.argType}] = None"
        }
      }
      else {
        s"${arg.safeArgName} : ${arg.argType}"
      }
    )
  }
}

// a mixin to ease generating the Random module
private[mxnet] trait RandomHelpers {
  self: GeneratorBase =>

/**
  * A generic type spec used in Symbol.random and NDArray.random modules
  * @param isSymbol Check if generate for Symbol
  * @param fullPackageSpec Check if leave the full name of the classTag
  * @return A formatted string for random Symbol/NDArray
  */
  protected def randomGenericTypeSpec(isSymbol: Boolean, fullPackageSpec: Boolean): String = {
    val classTag = if (fullPackageSpec) "scala.reflect.ClassTag" else "ClassTag"
    if (isSymbol) s"[T: SymbolOrScalar : $classTag]"
    else s"[T: NDArrayOrScalar : $classTag]"
  }

/**
  * Filter the operators to generate in the type-safe Symbol.random and NDArray.random
  * @param isSymbol Check if generate Symbol functions
  * @return List of functions
  */
  protected def typeSafeRandomFunctionsToGenerate(isSymbol: Boolean): List[Func] = {
    getBackEndFunctions(isSymbol)
      .filter(f => f.name.startsWith("_sample_") || f.name.startsWith("_random_"))
      .map(f => f.copy(name = f.name.stripPrefix("_")))
      // unify _random and _sample
      .map(f => unifyRandom(f, isSymbol))
      // deduplicate
      .groupBy(_.name)
      .mapValues(_.head)
      .values
      .toList
  }

  // unify call targets (random_xyz and sample_xyz) and unify their argument types
  private def unifyRandom(func: Func, isSymbol: Boolean): Func = {
    var typeConv = Set("org.apache.mxnet.NDArray", "org.apache.mxnet.Symbol",
      "Float", "Int")

    func.copy(
      name = func.name.replaceAll("(random|sample)_", ""),
      listOfArgs = func.listOfArgs
        .map(hackNormalFunc)
        .map(arg =>
          if (typeConv(arg.argType)) arg.copy(argType = "T")
          else arg
        )
      // TODO: some functions are non consistent in random_ vs sample_ regarding optionality
      // we may try to unify that as well here.
    )
  }

  /**
    * Hacks to manage the fact that random_normal and sample_normal have
    * non-consistent parameter naming in the back-end
    * this first one, merge loc/scale and mu/sigma
    * @param arg Argument need to modify
    * @return Arg case class with clean arg names
    */
  protected def hackNormalFunc(arg: Arg): Arg = {
    if (arg.argName == "loc") arg.copy(argName = "mu")
    else if (arg.argName == "scale") arg.copy(argName = "sigma")
    else arg
  }

  /**
    * This second one reverts this merge prior to back-end call
    * @param func Function case class
    * @return A string contains the implementation of random args
    */
  protected def unhackNormalFunc(func: Func): String = {
    if (func.name.equals("normal")) {
      s"""if(target.equals("random_normal")) {
         |  if(map.contains("mu")) { map("loc") = map("mu"); map.remove("mu")  }
         |  if(map.contains("sigma")) { map("scale") = map("sigma"); map.remove("sigma") }
         |}
       """.stripMargin
    } else {
      ""
    }

  }

}
