/*
This file is part of Textricator.
Copyright 2018 Measures for Justice Institute.

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU Affero General Public License version 3 as published by the
Free Software Foundation.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.mfj.textricator.form

import io.mfj.expr.*
import io.mfj.textricator.form.config.*
import io.mfj.textricator.record.Value
import io.mfj.textricator.text.Text
import io.mfj.textricator.text.groupRows

import org.slf4j.LoggerFactory

/**
 * Use a Finite State Machine config to parse
 * a sequence of Text to a sequence of StateValue,
 * which contains the FSM state and the value for that state.
 *
 * @param config FSM config
 * @param eventListener event listener
 */
class FsmParser(val config:FormParseConfig,
    private val eventListener:FsmEventListener? =null ) {

  companion object {
    private val log = LoggerFactory.getLogger(FsmParser::class.java)

    /**
     * Built-in variables.
     * The enum names match exactly the variables in the expression.
     */
    private enum class BuiltInVar(val type:ExDataType) {
      /** x coordinate of the upper-left corner of the text box */
      ulx(ExDataType.DOUBLE),
      /** y coordinate of the upper-left corner of the text box */
      uly(ExDataType.DOUBLE),
      /** x coordinate of the lower-right corner of the text box */
      lrx(ExDataType.DOUBLE),
      /** y coordinate of the lower-right corner of the text box */
      lry(ExDataType.DOUBLE),
      /** The text. */
      text(ExDataType.STRING),
      /** page number . */
      page(ExDataType.INTEGER),
      /** page number of the previous text */
      page_prev(ExDataType.INTEGER),
      /** font size */
      fontSize(ExDataType.DOUBLE),
      /** font name */
      font(ExDataType.STRING),
      /** text color */
      color(ExDataType.STRING),
      /** background color  */
      bgcolor(ExDataType.STRING),
      /** width of the text box */
      width(ExDataType.DOUBLE),
      /** height of the text box */
      height(ExDataType.DOUBLE),
      /** Difference in [ulx] from the previous text to this one */
      ulx_rel(ExDataType.DOUBLE),
      /** Difference in [uly] from the previous text to this one */
      uly_rel(ExDataType.DOUBLE),
      /** Difference in [lrx] from the previous text to this one */
      lrx_rel(ExDataType.DOUBLE),
      /** Difference in [lry] from the previous text to this one */
      lry_rel(ExDataType.DOUBLE),
    }

    private val builtInVarNames = BuiltInVar.values().map(BuiltInVar::name).toSet()

    // expr var type provider
    // If you add a type, add it to ParseState.vp
    private val VTP = ChainVarTypeProvider(
        MapVarTypeProvider(
            BuiltInVar
                .values()
                .map { builtInVar ->
                  builtInVar.name to builtInVar.type
                }
                .toMap()
        ),
        object: VarTypeProvider {
          override fun contains(varName:String):Boolean = true
          override operator fun get(varName: String): ExDataType = ExDataType.STRING
        } )

    /**
     * Compile the specified expression
     */
    private fun compile( e:String ): Expr {
      return ExprParser.parseToExpr(e, VTP)
    }

  }

  fun parse( texts:Sequence<Text> ): Sequence<StateValue> {

    // parse state is a passed object instead of object-level to keep this thread-safe
    val parseState = ParseState(config, eventListener)

    return texts.groupRows(config.maxRowDistance)

        // filter out header and footer
        .filter { text -> boundsFilter(text) }

        // group by state (run the FSM)
        .plus( null as Text? ) // sentinel
        .map { text -> parse( parseState, text ) }
        .filter { it != null }.map { it!! }

  }

  private class ParseState( private val config:FormParseConfig, private val eventListener:FsmEventListener? ) {

    val vars:MutableMap<String,String?> = mutableMapOf()

    // text currently being parsed
    private var current:Text? = null
    var text:Text?
      get() = current
      set(text) {
        last = current
        current = text
      }

    // last parsed text
    private var last:Text? = null
    val lastText:Text?
      get() = last

    var stateId:String
    var state:State

    private val buffer:MutableList<Text> = mutableListOf()

    // cache of parsed expression
    private val exprCache:MutableMap<String,Pair<String,Expr>> = mutableMapOf()

    /**
     * Get the expression and parsed Expr for the specified conditionId.
     */
    private fun getCondition( conditionId:String ):Pair<String,Expr> =
        exprCache.getOrPut( conditionId ) {
          val e = config.conditions[conditionId] ?: throw Exception( "Missing condition \"${conditionId}\"" )
          Pair( e, compile(e))
        }

    init {
      val stateId = config.initialState
      this.state = config.states[stateId] ?: throw Exception( "Missing initial state ${stateId}" )
      this.stateId = stateId
    }

    /** Expr variable provider */
    val vp: VarProvider = object : VarProvider {

      private fun noLast(source:String) = eventListener?.onNoPrevious(source)

      override fun contains(varName:String):Boolean = true // if it is not built in, anything else is allowed as String

      override operator fun get( varName: String ): Any? {
        return if ( builtInVarNames.contains(varName) ) {
          val builtInVar = BuiltInVar.valueOf(varName)
          when ( builtInVar ) {
            // built-ins
            BuiltInVar.ulx -> text!!.ulx
            BuiltInVar.uly -> text!!.uly
            BuiltInVar.lrx -> text!!.lrx
            BuiltInVar.lry -> text!!.lry
            BuiltInVar.text -> text!!.content
            BuiltInVar.page -> text!!.pageNumber
            BuiltInVar.page_prev -> if ( lastText != null ) lastText!!.pageNumber else { noLast( varName ); -1 }
            BuiltInVar.fontSize -> text!!.fontSize
            BuiltInVar.font -> text!!.font
            BuiltInVar.color -> text!!.color
            BuiltInVar.bgcolor -> text!!.backgroundColor
            BuiltInVar.width -> text!!.width
            BuiltInVar.height -> text!!.height
            BuiltInVar.ulx_rel -> if ( lastText != null ) text!!.ulx - lastText!!.ulx else { noLast( varName ); text!!.ulx }
            BuiltInVar.uly_rel -> if ( lastText != null ) text!!.uly - lastText!!.uly else { noLast( varName ); text!!.uly }
            BuiltInVar.lrx_rel -> if ( lastText != null ) text!!.lrx - lastText!!.lrx else { noLast( varName ); text!!.lrx }
            BuiltInVar.lry_rel -> if ( lastText != null ) text!!.lry - lastText!!.lry else { noLast( varName ); text!!.lry }
          }
        } else {
          vars[varName]
        }
      }

      override fun getKnownVars():Set<String> = builtInVarNames.plus( vars.keys )
    }

    /**
     * Determine if the current state matches the specified condition.
     */
    internal fun matches( conditionId:String ): Boolean {
      val (exprStr,expr) = getCondition(conditionId)

      val match = Eval.evaluate( expr, vp )

      eventListener?.onCheckCondition( conditionId, exprStr, match )

      return match
    }

    fun setState( stateId:String ): StateValue? {
      val ret = flush()
      //log.debug( "State = ${stateId} on pageNumber ${pageNumber}" )
      this.state = config.states[stateId] ?: throw Exception( "Missing state ${stateId}" )
      this.stateId = stateId
      return ret
    }

    /** Add the current text to the current state */
    fun addText() {
      buffer.add( text!! )
      last = text

      state.setVariables
          .filter { it.name != null }
          .forEach { vs:VariableSet ->
            val value = getVariableValue( vs.value )
            vars[vs.name!!] = value
            eventListener?.onVariableSet( stateId, vs.name, value )
            Unit
          }
    }

    fun flush(): StateValue? {
      val stateTexts:StateValue? = if ( buffer.isNotEmpty() ) {
        StateValue(buffer.first().pageNumber, stateId, state, parseValues())
      } else {
        null
      }
      buffer.clear()

      return stateTexts
    }

    private fun getVariableValue( value:String? ): String? =
        if ( value == null ) {
          null
        } else if ( value.startsWith( "{" ) && value.endsWith( "}" ) ) {
          vp[ value.drop(1).dropLast(1) ]?.toString()
        } else {
          value
        }

    /**
     * Convert the Texts into Strings, combining them according to [State.combineLimit].
     */
    private fun parseValues(): List<Value> {
      val values:MutableList<Value> = mutableListOf()

      var last:Text? = null
      for ( text in buffer ) {
        if ( last != null && shouldCombine( last, text ) ) {
          val lastValue = values.removeLast()
          val content = "${lastValue.text}${text.content}"
          val link = last.link
          if ( text.link != null && text.link != link ) {
            log.warn( "Skipping link \"${text.link}\" from \"${text.content}\"; it does not overwrite link \"${link}\" from \"${lastValue.text}\"")
          }
          values.add( Value( content, link ) )
        } else {
          values.add( Value(text.content,text.link) )
        }
        last = text
      }

      return values
    }

    private fun <T> MutableList<T>.removeLast(): T = removeAt( size-1 )

    private val combineLimitMap:Map<String,Float?> by lazy {
      val default:Float? = config.stateDefaults?.combineLimit
      config.states.mapValues { (stateId,state) -> state.combineLimit ?: default }
    }

    private fun shouldCombine( last:Text, text:Text):Boolean {
      val combineLimit:Float? = combineLimitMap[stateId]
      return ( combineLimit != null ) && ( text.ulx - last.lrx <= combineLimit )
    }


  }

  private fun parse( s:ParseState, text:Text? ): StateValue? {

    // process sentinel
    if ( text == null ) {
      //log.debug( "end of text sequence" )
      eventListener?.onFsmEnd()
      return s.flush()
    }

    if ( text.content.isBlank() ) return null

    s.text = text

    var ret:StateValue? = null // return value

    // if pageNumber change, flush if newPageState is set
    if ( text.pageNumber != ( s.lastText?.pageNumber ?: 1 ) && config.newPageState != null ) {
      ret = s.setState( config.newPageState!! )
      eventListener?.onPageStateChange(text.pageNumber, config.newPageState!! )
    }

    //text.debug()
    eventListener?.onText(text)

    // find transition
    var transition: Transition? = null
    for (t in s.state.transitions) {
      eventListener?.onCheckTransition(s.stateId,t.condition,t.nextState)
      val match = s.matches(t.condition)
      //log.debug("\tcheck transition {${t.condition}} -> [${t.nextState}] : ${match}")
      eventListener?.onCheckTransition(s.stateId,t.condition,t.nextState,match, if ( match ) t.message else null )
      if (match) {
        if ( t.message != null ) {
          log.warn( t.message )
        }
        transition = t
        break
      }
    }
    if (transition == null) {
      throw Exception("Page ${text.pageNumber} at \"${text.content.t()}\" - no valid transition from ${s.stateId}")
    }

    val nextStateId = transition.nextState

    if ( nextStateId == s.stateId ) {
      s.addText()
    } else {
      // state change

      if ( ret != null ) {
        // Somehow a pageNumber transition happened, and texts were already added.
        // Texts should not have been added.
        throw Exception( "BUG" )
      }

      eventListener?.onStateChange(text.pageNumber, nextStateId)
      ret = s.setState( nextStateId )
      s.addText()
    }

    return ret
  }

  private fun boundsFilter( text:Text): Boolean {
    // check header/footer/left/right

    val top = getLimit( config.header, text.pageNumber )
    if ( top != null && text.lry <= top ) {
      eventListener?.onText(text)
      eventListener?.onHeader(text)
      return false
    }

    val bottom = getLimit( config.footer, text.pageNumber )
    if ( bottom != null && text.uly >= bottom ) {
      eventListener?.onText(text)
      eventListener?.onFooter(text)
      return false
    }

    val left = getLimit( config.left, text.pageNumber )
    if ( left != null && text.lrx <= left ) {
      eventListener?.onText(text)
      eventListener?.onLeftMargin(text)
      return false
    }

    val right = getLimit( config.right, text.pageNumber )
    if ( right != null && text.ulx >= right ) {
      eventListener?.onText(text)
      eventListener?.onRightMargin(text)
      return false
    }

    return true
  }

  private fun getLimit( rules:DefaultAndPages, pageNumber:Int ): Float? {
    val s = if ( rules.pages.containsKey( pageNumber ) ) {
      rules.pages[pageNumber]!!
    } else {
      rules.default
    }
    return s?.toFloat()
  }

  private fun Text.debug() {
    log.debug("============================")
    log.debug("text: \"${content}\"")
    log.debug("\tpageNumber: ${pageNumber} ul:[ ${ulx} , ${uly} ] lr: [ ${lrx} , ${lry} ]")
    log.debug("\tfont: ${font} - ${fontSize}")
  }

}

private fun String?.t(l:Int = 20):String = when {
  this == null -> ""
  this.length <= l -> this
  else -> this.take(l) + "..."
}
