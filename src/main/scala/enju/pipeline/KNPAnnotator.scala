package enju.pipeline

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Properties
import scala.util.matching.Regex
import scala.xml._

class KNPAnnotator(val name: String, val props: Properties) extends SentencesAnnotator {
  val knpCommand: String = props.getProperty("knp.command", "knp")

  //for KNP 4.12 (-ne option is unneed)
  lazy private[this] val knpProcess = new java.lang.ProcessBuilder(knpCommand, "-tab", "-anaphora").start
  lazy private[this] val knpIn = new BufferedReader(new InputStreamReader(knpProcess.getInputStream, "UTF-8"))
  lazy private[this] val knpOut = new BufferedWriter(new OutputStreamWriter(knpProcess.getOutputStream, "UTF-8"))

  /**
    * Close the external process and the interface
    */
  override def close() {
    knpOut.close()
    knpIn.close()
    knpProcess.destroy()
  }

  def isBasicPhrase(knpStr:String) : Boolean = knpStr(0) == '+'
  def isChunk(knpStr:String) : Boolean = knpStr(0) == '*'
  def isDocInfo(knpStr:String) : Boolean = knpStr(0) == '#'
  def isEOS(knpStr:String) : Boolean = knpStr == "EOS"
  def isToken(knpStr:String) : Boolean = ! isBasicPhrase(knpStr) && ! isChunk(knpStr) && ! isDocInfo(knpStr) && ! isEOS(knpStr)

  private def tid(sindex: String, tindex: Int) = sindex + "_tok" + tindex.toString
  private def cid(sindex: String, cindex: Int) = sindex + "_chu" + cindex
  private def bpid(sindex: String, bpindex: Int) = sindex + "_bp" + bpindex.toString
  private def bpdid(sindex: String, bpdindex: Int) = sindex + "_bpdep" + bpdindex.toString
  private def depid(sindex: String, depindex: Int) = sindex + "_dep" + depindex.toString
  private def crid(sindex: String, crindex:Int) = sindex + "_cr" + crindex.toString
  private def corefid(sindex: String, corefindex:Int) = sindex + "_coref" + corefindex.toString
  private def parid(sindex: String, parindex:Int) = sindex + "_par" + parindex.toString
  private def neid(sindex: String, neindex:Int) = sindex + "_ne" + neindex.toString

  def getTokens(knpResult:Seq[String], sid:String) : Node = {
    var tokenIndex = 0

    val nodes = knpResult.filter(s =>  s(0) != '#' && s(0) != '*' && s(0) != '+' && s != "EOS").map{
      s =>
      val tok = s.split(' ')

      val surf              = tok(0)
      val reading           = tok(1)
      val base              = tok(2)
      val pos               = tok(3)
      val posId            = tok(4)
      val pos1              = tok(5)
      val pos1Id           = tok(6)
      val inflectionType    = tok(7)
      val inflectionTypeId = tok(8)
      val inflectionForm    = tok(9)
      val inflectionFormId = tok(10)
      val features          = tok.drop(11).mkString(" ")
      val pos2           = None
      val pos3           = None
      val pronounce      = None

      val node = <token
      id={ tid(sid, tokenIndex) }
      surf={ surf }
      pos={ pos }
      pos1={ pos1 }
      pos2={ pos2 }
      pos3={ pos3 }
      inflectionType={ inflectionType }
      inflectionForm={ inflectionForm }
      base={ base }
      reading={ reading }
      pronounce={ pronounce }
      pos_id={ posId }
      pos1_id={ pos1Id }
      inflectionType_id={ inflectionTypeId }
      inflectionForm_id={ inflectionFormId }
      features={ features }/>
      tokenIndex += 1
      node
    }

    <tokens>{ nodes }</tokens>
  }

  def getBasicPhrases(knpResult:Seq[String], sid:String) : NodeSeq = {
    var tokIdx = -1

    val basicPhraseBoundaries = knpResult.zipWithIndex.filter(x=>isBasicPhrase(x._1)).map(_._2) :+ knpResult.size
    val basicPhrases = basicPhraseBoundaries.sliding(2).toSeq.zipWithIndex map { case (Seq(b,  e), bpIdx) =>
      val knpStr = knpResult(b)
      val tokenIDs = (b + 1 until e).filter(i=>isToken(knpResult(i))) map { _ =>
        tokIdx += 1
        tid(sid, tokIdx)
      }
      <basic_phrase id={ bpid(sid, bpIdx) } tokens={ tokenIDs.mkString(" ") } features={ knpStr.split(" ")(2) } />
    }
    <basic_phrases>{ basicPhrases }</basic_phrases>
  }

  def getChunks(knpResult:Seq[String], sid:String) : NodeSeq = {
    var tokIdx = -1

    val chunkBoundaries = knpResult.zipWithIndex.filter(x=>isChunk(x._1)).map(_._2) :+ knpResult.size
    val chunks = chunkBoundaries.sliding(2).toSeq.zipWithIndex map { case (Seq(b, e), chunkIdx) =>
      val knpStr = knpResult(b)
      val tokenIDs = (b + 1 until e).filter(i=>isToken(knpResult(i))) map { _ =>
        tokIdx += 1
        tid(sid, tokIdx)
      }
      <chunk id={ cid(sid, chunkIdx) } tokens={ tokenIDs.mkString(" ") } features={ knpStr.split(" ")(2) }/>
    }
    <chunks>{ chunks }</chunks>
  }

  def getBasicPhraseDependencies(knpResult:Seq[String], sid:String) : NodeSeq = {
    val bpdepStrs = knpResult.filter(knpStr => isBasicPhrase(knpStr))
    val bpdepNum = bpdepStrs.length
    var bpdInd = 0

    // init: remove the last dependency (+ -1D ...)
    val dpdXml = bpdepStrs.init.map{
      bpdepStr =>
      val hd = bpid(sid, bpdepStr.split(" ")(1).init.toInt)
      val dep = bpid(sid, bpdInd)
      val lab = bpdepStr.split(" ")(1).last.toString

      val ans = <basic_phrase_dependency id={bpdid(sid, bpdInd)} head={hd} dependent={dep} label={lab} />
      bpdInd += 1

      ans
    }

    <basic_phrase_dependencies root={bpid(sid, bpdepNum-1)} >{ dpdXml }</basic_phrase_dependencies>
  }


  def getDependencies(knpResult:Seq[String], sid:String) : NodeSeq = {
    val depStrs = knpResult.filter(knpStr => isChunk(knpStr))
    val depNum = depStrs.length
    var depInd = 0


    // init: remove the last dependency (* -1D ...)
    val depXml = depStrs.init.map{
      depStr =>
      val hd = cid(sid, depStr.split(" ")(1).init.toInt)
      val dep = cid(sid, depInd)
      val lab = depStr.split(" ")(1).last.toString

      val ans = <dependency id={depid(sid, depInd)} head={hd} dependent={dep} label={lab} />
      depInd += 1

      ans
    }

    <dependencies root={cid(sid, depNum-1)} >{ depXml }</dependencies>
  }

  // "格解析結果:走る/はしる:動13:ガ/C/太郎/0/0/1;ヲ/U/-/-/-/-;ニ/U/-/-/-/-;ト/U/-/-/-/-;デ/U/-/-/-/-;カラ/U/-/-/-/-;ヨリ/U/-/-/-/-;マデ/U/-/-/-/-;時間/U/-/-/-/-;外の関係/U/-/-/-/-;ノ/U/-/-/-/-;修飾/U/-/-/-/-;トスル/U/-/-/-/-;ニオク/U/-/-/-/-;ニカンスル/U/-/-/-/-;ニヨル/U/-/-/-/-;ヲフクメル/U/-/-/-/-;ヲハジメル/U/-/-/-/-;ヲノゾク/U/-/-/-/-;ヲツウジル/U/-/-/-/-
  def getCaseRelations(knpResult:Seq[String], tokensXml:NodeSeq, bpsXml:NodeSeq, sid:String) : NodeSeq = {
    var crInd = 0

    val ans = knpResult.filter(str => isBasicPhrase(str)).zipWithIndex.filter(tpl => tpl._1.contains("<格解析結果:")).map{
      tpl =>
      val str = tpl._1
      val bpInd = tpl._2

      val pattern1 = "<格解析結果:[^>]+>".r
      val sp = pattern1.findFirstIn(str).getOrElse("<>").init.tail.split(":")
      val caseResults = sp(3)  //  ガ/C/太郎/0/0/1;ヲ/ ...
      val hd = bpid(sid, bpInd)

      caseResults.split(";").map{
        str =>
        val caseResult = str.split("/")
        val lab = caseResult(0)
        val fl = caseResult(1)

        // assumes that sentence_id is as "s0"
        val dependBpid = if (caseResult(3) == "-") None else Some(bpid("s" + (sid.tail.toInt - caseResult(4).toInt), caseResult(3).toInt))
        val dependTok : Option[String]= dependBpid.map{
          bpid =>
          //find a token whose surf equals to case_result(2)

          val dependBp : Option[NodeSeq] = (bpsXml \\ "basic_phrase").find(bp => (bp \ "@id").toString == bpid)
          val tokenIds : List[String] = dependBp.map(bp => (bp \ "@tokens").toString.split(' ').toList).getOrElse(List() : List[String])
          tokenIds.find(tokId => ((tokensXml \ "token").find(tok => (tok \ "@id").toString == tokId).getOrElse(<error/>) \ "@surf").toString == caseResult(2))
        }.flatten

        val ansXml = <case_relation id={crid(sid, crInd)} head={hd} depend={ dependTok.getOrElse("unk") } label={lab} flag={fl} />
        crInd += 1
        ansXml
      }
    }.flatten

    <case_relations>{ ans }</case_relations>
  }

  def getCoreferences(bp_xml:NodeSeq, sid:String) : Node = {
    val eidHash = scala.collection.mutable.LinkedHashMap[Int, String]()

    (bp_xml \ "basic_phrase").map{
      bp =>
      val bpid = (bp \ "@id").toString
      val feature : String = (bp \ "@features").text

      val pattern = new Regex("""\<EID:(\d+)\>""", "eid")
      val eid = pattern.findFirstMatchIn(feature).map(m => m.group("eid").toInt).getOrElse(-1)

      if (eidHash.contains(eid)){
        eidHash(eid) = eidHash(eid) + " " + bpid
      }
      else{
        eidHash(eid) = bpid
      }
    }

    val ans = eidHash.map{
      case (eid, bps) =>
        <coreference id={corefid(sid, eid)} basic_phrases={bps} />
    }

    <coreferences>{ ans }</coreferences>
  }

  def getPredicateArgumentRelations(knpResult:Seq[String], sid:String) : Node = {
    var parInd = 0

    //<述語項構造:飲む/のむ:動1:ガ/N/麻生太郎/1;ヲ/C/コーヒー/2>
    val pattern = new Regex("""\<述語項構造:[^:]+:[^:]+:(.+)\>""", "args")

    val ans = knpResult.filter(knpStr => isBasicPhrase(knpStr)).zipWithIndex.filter(tpl => tpl._1.contains("<述語項構造:")).map{
      tpl =>
      val knpStr = tpl._1
      val bpInd = tpl._2

      val argsOpt = pattern.findFirstMatchIn(knpStr).map(m => m.group("args"))
      argsOpt.map{
        args =>
        args.split(";").map{
          arg =>
          val sp = arg.split("/")
          val label = sp(0)
          val flag = sp(1)
          //val name = sp(2)
          val eid = sp(3).toInt

          val ans = <predicate_argument_relation id={parid(sid, parInd)} predicate={bpid(sid, bpInd)} argument={corefid(sid, eid)} label={label} flag={flag} />
          parInd += 1
          ans
        }
      }.getOrElse(NodeSeq.Empty)
    }

    <predicate_argument_relations>{ ans }</predicate_argument_relations>
  }

  def getNamedEntities(knpResult:Seq[String], sid:String) : Node = {
    var neInd = 0
    var lastTag = "N" //for convenience, use "N" as non-tag of "B/I/E/S"
    var tempTokens : Seq[String] = Seq()
    var tempLabel = ""

    val pattern = new Regex("""\<NE:([A-Z]+):([BIES])\>""", "reLabel", "reTag")
    var ans = NodeSeq.Empty

    for (tpl <- knpResult.filter(knpStr => isToken(knpStr)).zipWithIndex){
      val knpStr = tpl._1
      val tokInd = tpl._2
      val (reLabel, reTag) = pattern.findFirstMatchIn(knpStr).map(m => (m.group("reLabel"), m.group("reTag"))).getOrElse(("", "N"))

      if ((lastTag == "N" && reTag == "B") || (lastTag == "N" && reTag == "S")){
        lastTag = reTag
        tempTokens = tempTokens :+ tid(sid, tokInd)
        tempLabel = reLabel
      }
      else if((lastTag == "S" && reTag == "N") || (lastTag == "B" && reTag == "N") || (lastTag == "E" && reTag == "N")){
        ans = ans :+ <named_entity id={neid(sid, neInd)} tokens={tempTokens.mkString(" ")} label={tempLabel} />

        lastTag = reTag
        neInd += 1
        tempTokens = Seq()
        tempLabel = ""
      }
      else if((lastTag == "B" && reTag == "I") || (lastTag == "B" && reTag == "E") || (lastTag == "I" && reTag == "E")){
        lastTag = reTag
        tempTokens = tempTokens :+ tid(sid, tokInd)
      }
    }

    if(lastTag == "S" || (lastTag == "E")){
      ans = ans :+ <named_entity id={neid(sid, neInd)} tokens={tempTokens.mkString(" ")} label={tempLabel} />
    }

    <named_entities>{ ans }</named_entities>
  }

  def makeXml(sentence:Node, knpResult:Seq[String], sid:String) : Node = {
    val knpTokens = getTokens(knpResult, sid)
    val sentenceWithTokens = enju.util.XMLUtil.replaceAll(sentence, "tokens")(node => knpTokens)
    val basicPhrases = getBasicPhrases(knpResult, sid)
    val sentenceWithBps = enju.util.XMLUtil.addChild(sentenceWithTokens, basicPhrases)
    val sentenceWithChunks = enju.util.XMLUtil.addChild(sentenceWithBps, getChunks(knpResult, sid))
    val sentenceWithBpdeps = enju.util.XMLUtil.addChild(sentenceWithChunks, getBasicPhraseDependencies(knpResult, sid))
    val sentenceWithDeps = enju.util.XMLUtil.addChild(sentenceWithBpdeps, getDependencies(knpResult, sid))
    val sentenceWithCaseRelations = enju.util.XMLUtil.addChild(sentenceWithDeps, getCaseRelations(knpResult, knpTokens, basicPhrases, sid))
    val sentenceWithCoreferences = enju.util.XMLUtil.addChild(sentenceWithCaseRelations, getCoreferences(basicPhrases, sid))
    val sentenceWithPredicateArguments = enju.util.XMLUtil.addChild(sentenceWithCoreferences, getPredicateArgumentRelations(knpResult, sid))
    val sentenceWithNamedEntity = enju.util.XMLUtil.addChild(sentenceWithPredicateArguments, getNamedEntities(knpResult, sid))

    sentenceWithNamedEntity
  }

  def recovJumanOutput(juman_tokens:Node) : Seq[String] = {
    (juman_tokens \\ "token").map{
      tok =>
      val tokStr = (tok \ "@surf") + " " + (tok \ "@reading") + " " + (tok \ "@base") + " " +
      (tok \ "@pos") + " " + (tok \ "@pos_id") + " " +
      (tok \ "@pos1") + " " + (tok \ "@pos1_id") + " " +
      (tok \ "@inflectionType") + " " + (tok \ "@inflectionType_id") + " " +
      (tok \ "@inflectionForm") + " " + (tok \ "@inflectionForm_id") + " " +
        (tok \ "@features").text + "\n"

      val tokenAltSeq = (tok \ "token_alt")

      if (tokenAltSeq.isEmpty){
        Seq(tokStr)
      }
      else{
        tokStr +: tokenAltSeq.map{
          tokAlt =>
          "@ " + (tokAlt \ "@surf") + " " + (tokAlt \ "@reading") + " " + (tokAlt \ "@base") + " " +
          (tokAlt \ "@pos") + " " + (tokAlt \ "@pos_id") + " " +
          (tokAlt \ "@pos1") + " " + (tokAlt \ "@pos1_id") + " " +
          (tokAlt \ "@inflectionType") + " " + (tokAlt \ "@inflectionType_id") + " " +
          (tokAlt \ "@inflectionForm") + " " + (tokAlt \ "@inflectionForm_id") + " " +
            (tokAlt \ "@features").text + "\n"
        }
      }
    }.foldLeft(List() : List[String])(_ ::: _.toList).toSeq :+ "EOS\n"
  }

  override def newSentenceAnnotation(sentence: Node): Node = {
    def runKNP(jumanTokens:Node): Seq[String] = {
      knpOut.write(recovJumanOutput(jumanTokens).mkString)
      knpOut.flush()

      Iterator.continually(knpIn.readLine()).takeWhile(_ != "EOS").toSeq :+ "EOS"
    }

    val sindex = (sentence \ "@id").toString
    val jumanTokens = (sentence \ "tokens").head
    val knpResult = runKNP(jumanTokens)

    makeXml(sentence, knpResult, sindex)
  }

  override def requires = Set(Annotator.JaTokenize)
  override def requirementsSatisfied = Set(Annotator.JaChunk, Annotator.JaDependency, Annotator.NamedEntity)
}
