package io.kaitai.struct.languages

import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.exprlang.DataType._
import io.kaitai.struct.format._
import io.kaitai.struct.languages.components._
import io.kaitai.struct.translators.{BaseTranslator, CSharpTranslator, TypeProvider}
import io.kaitai.struct.{LanguageOutputWriter, Utils}

class CSharpCompiler(verbose: Boolean, out: LanguageOutputWriter, namespace: String = "Kaitai")
  extends LanguageCompiler(verbose, out)
    with AllocateIOLocalVar
    with EveryReadIsExpression
    with NoNeedForFullClassPath {
  import CSharpCompiler._

  override def getStatic = CSharpCompiler

  override def fileHeader(topClassName: String): Unit = {
    out.puts(s"// $headerComment")

    var ns = "Kaitai"
    if (!namespace.isEmpty) ns = namespace

    out.puts
    out.puts("using System;")
    out.puts("using System.Collections.Generic;")
    if (ns != "Kaitai") out.puts("using Kaitai;")
    out.puts

    out.puts(s"namespace $ns")
    out.puts(s"{")
    out.inc
  }

  override def fileFooter(topClassName: String): Unit = {
    out.dec
    out.puts("}")
  }

  override def classHeader(name: String): Unit = {

    out.puts(s"public partial class ${type2class(name)} : $kstructName")
    out.puts(s"{")
    out.inc

    out.puts(s"public static ${type2class(name)} FromFile(string fileName)")
    out.puts(s"{")
    out.inc
    out.puts(s"return new ${type2class(name)}(new $kstreamName(fileName));")
    out.dec
    out.puts("}")
  }

  override def classFooter(name: String): Unit = fileFooter(name)

  override def classConstructorHeader(name: String, parentClassName: String, rootClassName: String): Unit = {
    out.puts
    out.puts(s"public ${type2class(name)}($kstreamName io, ${type2class(parentClassName)} parent = null, ${type2class(rootClassName)} root = null) : base(io)")
    out.puts(s"{")
    out.inc
    out.puts(s"${privateMemberName(ParentIdentifier)} = parent;")

    if (name == rootClassName)
      out.puts(s"${privateMemberName(RootIdentifier)} = root ?? this;")
    else
      out.puts(s"${privateMemberName(RootIdentifier)} = root;")

    out.puts("_parse();")
    out.dec
    out.puts("}")
    out.puts

    out.puts("private void _parse()")
    out.puts("{")
    out.inc
  }

  override def classConstructorFooter: Unit = fileFooter(null)

  override def attributeDeclaration(attrName: Identifier, attrType: BaseType, condSpec: ConditionalSpec): Unit = {
    out.puts(s"private ${kaitaiType2NativeType(attrType)} ${privateMemberName(attrName)};")
  }

  override def attributeReader(attrName: Identifier, attrType: BaseType): Unit = {
    out.puts(s"public ${kaitaiType2NativeType(attrType)} ${publicMemberName(attrName)} { get { return ${privateMemberName(attrName)}; } }")
  }

  override def attrFixedContentsParse(attrName: Identifier, contents: Array[Byte]): Unit = {
    out.puts(s"${privateMemberName(attrName)} = $normalIO.EnsureFixedContents(${contents.length}, new byte[] { ${contents.mkString(", ")} });")
  }

  override def attrProcess(proc: ProcessExpr, varSrc: Identifier, varDest: Identifier): Unit = {
    val srcName = privateMemberName(varSrc)
    val destName = privateMemberName(varDest)

    proc match {
      case ProcessXor(xorValue) =>
        out.puts(s"$destName = $normalIO.ProcessXor($srcName, ${expression(xorValue)});")
      case ProcessZlib =>
        out.puts(s"$destName = $normalIO.ProcessZlib($srcName);")
      case ProcessRotate(isLeft, rotValue) =>
        val expr = if (isLeft) {
          expression(rotValue)
        } else {
          s"8 - (${expression(rotValue)})"
        }
        out.puts(s"$destName = $normalIO.ProcessRotateLeft($srcName, $expr, 1);")
    }
  }

  override def allocateIO(varName: Identifier, rep: RepeatSpec): String = {
    val privateVarName = privateMemberName(varName)

    val ioName = s"io_$privateVarName"

    val args = rep match {
      case RepeatEos | RepeatExpr(_) => s"$privateVarName[$privateVarName.Count - 1]"
      case NoRepeat => privateVarName
    }

    out.puts(s"var $ioName = new $kstreamName($args);")
    ioName
  }

  override def useIO(ioEx: expr): String = {
    out.puts(s"$kstreamName io = ${expression(ioEx)};")
    "io"
  }

  override def pushPos(io: String): Unit =
    out.puts(s"long _pos = $io.Pos;")

  override def seek(io: String, pos: Ast.expr): Unit =
    out.puts(s"$io.Seek(${expression(pos)});")

  override def popPos(io: String): Unit =
    out.puts(s"$io.Seek(_pos);")

  override def condIfHeader(expr: expr): Unit = {
    out.puts(s"if (${expression(expr)}) {")
    out.inc
  }

  override def condIfFooter(expr: expr): Unit = fileFooter(null)

  override def condRepeatEosHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = new List<byte[]>();")
    out.puts(s"${privateMemberName(id)} = new ${kaitaiType2NativeType(ArrayType(dataType))}();")
    out.puts(s"while (!$io.IsEof()) {")
    out.inc
  }

  override def handleAssignmentRepeatEos(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)}.Add($expr);")
  }

  override def condRepeatEosFooter: Unit = fileFooter(null)

  override def condRepeatExprHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, repeatExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = new List<byte[]>((int) (${expression(repeatExpr)}));")
    out.puts(s"${privateMemberName(id)} = new ${kaitaiType2NativeType(ArrayType(dataType))}((int) (${expression(repeatExpr)}));")
    out.puts(s"for (var i = 0; i < ${expression(repeatExpr)}; i++) {")
    out.inc
  }

  override def handleAssignmentRepeatExpr(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)}.Add($expr);")
  }

  override def condRepeatExprFooter: Unit = fileFooter(null)

  override def condRepeatUntilHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, untilExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = new List<byte[]>();")
    out.puts(s"${privateMemberName(id)} = new ${kaitaiType2NativeType(ArrayType(dataType))}();")
    out.puts("{")
    out.inc
    out.puts(s"${kaitaiType2NativeType(dataType)} ${translator.doName("_")};")
    out.puts("do {")
    out.inc
  }

  override def handleAssignmentRepeatUntil(id: Identifier, expr: String): Unit = {
    out.puts(s"${translator.doName("_")} = $expr;")
    out.puts(s"${privateMemberName(id)}.Add(${translator.doName("_")});")
  }

  override def condRepeatUntilFooter(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, untilExpr: expr): Unit = {
    _currentIteratorType = Some(dataType)
    out.dec
    out.puts(s"} while (!(${expression(untilExpr)}));")
    out.dec
    out.puts("}")
  }

  override def handleAssignmentSimple(id: Identifier, expr: String): Unit = {
    out.puts(s"${privateMemberName(id)} = $expr;")
  }

  override def parseExpr(dataType: BaseType, io: String): String = {
    dataType match {
      case t: ReadableType =>
        s"$io.Read${Utils.capitalize(t.apiCall)}()"
      // Aw, crap, can't use interpolated strings here: https://issues.scala-lang.org/browse/SI-6476
      case StrByteLimitType(bs, encoding) =>
        s"$io.ReadStrByteLimit(${expression(bs)}, " + '"' + encoding + "\")"
      case StrEosType(encoding) =>
        io + ".ReadStrEos(\"" + encoding + "\")"
      case StrZType(encoding, terminator, include, consume, eosError) =>
        io + ".ReadStrz(\"" + encoding + '"' + s", $terminator, $include, $consume, $eosError)"
      case EnumType(enumName, t) =>
        s"((${type2class(enumName)}) ${parseExpr(t, io)})"
      case BytesLimitType(size, _) =>
        s"$io.ReadBytes(${expression(size)})"
      case BytesEosType(_) =>
        s"$io.ReadBytesFull()"
      case t: UserType =>
        s"new ${types2class(t.name)}($io, this, ${privateMemberName(RootIdentifier)})"
    }
  }

  override def switchStart(id: Identifier, on: Ast.expr): Unit =
    out.puts(s"switch (${expression(on)}) {")

  override def switchCaseStart(condition: Ast.expr): Unit = {
    out.puts(s"case ${expression(condition)}:")
    out.inc
  }

  override def switchCaseEnd(): Unit = {
    out.puts("break;")
    out.dec
  }

  override def switchEnd(): Unit =
    out.puts("}")

  override def instanceDeclaration(attrName: InstanceIdentifier, attrType: BaseType, condSpec: ConditionalSpec): Unit = {
    out.puts(s"private ${kaitaiType2NativeType(attrType)} ${privateMemberName(attrName)};")
  }

  override def instanceHeader(className: String, instName: InstanceIdentifier, dataType: BaseType): Unit = {
    out.puts(s"public ${kaitaiType2NativeType(dataType)} ${publicMemberName(instName)}")
    out.puts("{")
    out.inc
    out.puts("get")
    out.puts("{")
    out.inc

    // Move this out of `instanceCheckCacheAndReturn` because we need to know the datatype
    out.puts(s"if (${privateMemberName(instName)} != default(${kaitaiType2NativeType(dataType)}))")
    out.inc
    instanceReturn(instName)
    out.dec
  }

  override def instanceFooter: Unit = {
    out.dec
    out.puts("}")
    out.dec
    out.puts("}")
  }

  override def instanceCheckCacheAndReturn(instName: InstanceIdentifier): Unit = {
    // See instanceHeader - the data type is needed, so the check is added in the instance header instead
  }

  override def instanceReturn(instName: InstanceIdentifier): Unit = {
    out.puts(s"return ${privateMemberName(instName)};")
  }

  override def enumDeclaration(curClass: String, enumName: String, enumColl: Map[Long, String]): Unit = {
    val enumClass = type2class(enumName)

    out.puts
    out.puts(s"public enum $enumClass")
    out.puts(s"{")
    out.inc

    enumColl.foreach { case (id, label) =>
      out.puts(s"${Utils.upperCamelCase(label)} = $id,")
    }

    out.dec
    out.puts("}")
  }

  def idToStr(id: Identifier): String = {
    id match {
      case SpecialIdentifier(name) => name
      case NamedIdentifier(name) => Utils.lowerCamelCase(name)
      case InstanceIdentifier(name) => Utils.lowerCamelCase(name)
      case RawIdentifier(innerId) => "_raw_" + idToStr(innerId)
    }
  }

  override def publicMemberName(id: Identifier): String = {
    id match {
      case SpecialIdentifier(name) => s"M${Utils.upperCamelCase(name)}"
      case NamedIdentifier(name) => Utils.upperCamelCase(name)
      case InstanceIdentifier(name) => Utils.upperCamelCase(name)
      case RawIdentifier(innerId) => s"M_Raw${publicMemberName(innerId)}"
    }
  }

  override def privateMemberName(id: Identifier): String = {
    id match {
      case SpecialIdentifier(name) => s"m${Utils.lowerCamelCase(name)}"
      case _ => s"_${idToStr(id)}"
    }
  }
}

object CSharpCompiler extends LanguageCompilerStatic
  with StreamStructNames
  with UpperCamelCaseClasses {
  override def getTranslator(tp: TypeProvider): BaseTranslator = new CSharpTranslator(tp)
  override def indent: String = "    "
  override def outFileName(topClassName: String): String = s"${type2class(topClassName)}.cs"

  /**
    * Determine .NET data type corresponding to a KS data type.
    *
    * @param attrType KS data type
    * @return .NET data type
    */
  def kaitaiType2NativeType(attrType: BaseType): String = {
    attrType match {
      case Int1Type(false) => "byte"
      case IntMultiType(false, Width2, _) => "ushort"
      case IntMultiType(false, Width4, _) => "uint"
      case IntMultiType(false, Width8, _) => "ulong"

      case Int1Type(true) => "sbyte"
      case IntMultiType(true, Width2, _) => "short"
      case IntMultiType(true, Width4, _) => "int"
      case IntMultiType(true, Width8, _) => "long"

      case FloatMultiType(Width4, _) => "float"
      case FloatMultiType(Width8, _) => "double"

      case CalcIntType => "int"
      case CalcFloatType => "double"

      case _: StrType => "string"
      case _: BytesType => "byte[]"

      case t: UserType => types2class(t.name)
      case EnumType(name, _) => type2class(name)

      case ArrayType(inType) => s"List<${kaitaiType2NativeType(inType)}>"

      // TODO: implement proper type derivation
      case SwitchType(_, _) => kstructName
    }
  }

  def types2class(names: List[String]) = names.map(x => type2class(x)).mkString(".")

  override def kstructName = "KaitaiStruct"
  override def kstreamName = "KaitaiStream"

  override def type2class(name: String): String = name match {
    case "kaitai_struct" => kstructName
    case "kaitai_stream" => kstreamName
    case _ => Utils.upperCamelCase(name)
  }
}
