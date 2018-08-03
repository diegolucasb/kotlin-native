/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.serialization


import org.jetbrains.kotlin.protobuf.*
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedInputStream.*
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.backend.konan.ir.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.backend.konan.llvm.base64Encode
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.ClassKind.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.IrTemporaryVariableDescriptorImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.toKotlinType as utilToKotlinType
import org.jetbrains.kotlin.metadata.KonanIr
import org.jetbrains.kotlin.metadata.KonanIr.IrConst.ValueCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrDeclarator.DeclaratorCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrOperation.OperationCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrStatement.StatementCase
import org.jetbrains.kotlin.metadata.KonanIr.IrType.KindCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrTypeArgument.KindCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrVarargElement.VarargElementCase
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.ir.util.IrDeserializer
import org.jetbrains.kotlin.backend.common.WithLogger
import org.jetbrains.kotlin.backend.konan.descriptors.propertyIfAccessor
import org.jetbrains.kotlin.backend.konan.irasdescriptors.fqNameSafe
import org.jetbrains.kotlin.backend.konan.irasdescriptors.isReal
import org.jetbrains.kotlin.backend.konan.irasdescriptors.name
import org.jetbrains.kotlin.backend.konan.library.SerializedIr
import org.jetbrains.kotlin.backend.konan.llvm.isExported
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.resolve.descriptorUtil.classId


// TODO: move me somewhere

/**
 * Implementation of given method.
 *
 * TODO: this method is actually a part of resolve and probably duplicates another one
 */
internal fun IrSimpleFunction.resolveFakeOverrideMaybeAbstract(): IrSimpleFunction {
    if (this.isReal) {
        return this
    }

    val visited = mutableSetOf<IrSimpleFunction>()
    val realSupers = mutableSetOf<IrSimpleFunction>()

    fun findRealSupers(function: IrSimpleFunction) {
        //println("### findRealSupers()")
        if (function in visited) { return }
        visited += function
        if (function.isReal) {
            //println("isreal")
            realSupers += function
        } else {


            //println("### overriddenSymbols for ${function.name} in ${function.parent.fqNameSafe.toString()}")
            //this.overriddenSymbols.forEach { println(function.name) }
            //println(".")

            function.overriddenSymbols.forEach { findRealSupers(it.owner) }
        }
    }

    findRealSupers(this)

    if (realSupers.size > 1) {
        visited.clear()

        fun excludeOverridden(function: IrSimpleFunction) {
            if (function in visited) return
            visited += function
            function.overriddenSymbols.forEach {
                realSupers.remove(it.owner)
                excludeOverridden(it.owner)
            }
        }

        realSupers.toList().forEach { excludeOverridden(it) }
    }

    //println("### realSupers for ${this.name}")
    //realSupers.forEach { println("${it.name} in ${it.parent.fqNameSafe.toString()}") }

    return realSupers.first() /*{ it.modality != Modality.ABSTRACT } */
}


/**
 * Implementation of given method.
 *
 * TODO: this method is actually a part of resolve and probably duplicates another one
 */
internal fun <T : CallableMemberDescriptor> T.resolveFakeOverrideMaybeAbstract(): Set<T> {
    if (this.kind.isReal) {
        return setOf(this)
    } else {
        val overridden = OverridingUtil.getOverriddenDeclarations(this)
        val filtered = OverridingUtil.filterOutOverridden(overridden)
        // TODO: is it correct to take first?
        @Suppress("UNCHECKED_CAST")
        return filtered as Set<T>
    }
}

internal class IrModuleSerialization(val logger: WithLogger,
                            val declarationTable: DeclarationTable
                            /*stringTable: KonanStringTable,
                            rootFunctionSerializer: KonanDescriptorSerializer,
                            private var rootFunction: FunctionDescriptor*/) {

    private val loopIndex = mutableMapOf<IrLoop, Int>()
    private var currentLoopIndex = 0


    private fun serializeCoordinates(start: Int, end: Int): KonanIr.Coordinates {
        return KonanIr.Coordinates.newBuilder()
                .setStartOffset(start)
                .setEndOffset(end)
                .build()
    }

    private fun serializeTypeArguments(call: IrMemberAccessExpression): KonanIr.TypeArguments {
        val proto = KonanIr.TypeArguments.newBuilder()
        for (i in 0 until call.typeArgumentsCount) {
            proto.addTypeArgument(serializeIrType(call.getTypeArgument(i)!!))
        }
        return proto.build()
    }

    /* ------- IrSymbols -------------------------------------------------------- */




    fun serializeDescriptorReference(symbol: IrSymbol): KonanIr.DescriptorReference? {

        val declaration = symbol.owner as IrDeclaration
        val descriptor = declaration.descriptor
        if (!declaration.isExported()) return null
        if (symbol.owner is IrAnonymousInitializer) return null
        if (descriptor is ValueDescriptor || descriptor is TypeParameterDescriptor) return null

        val parent = descriptor.containingDeclaration!!
        val (packageFqName, classFqName) = when (parent) {
            is ClassDescriptor -> {
                val classId = parent.classId ?: return null
                Pair(classId.getPackageFqName().toString(), classId.getRelativeClassName().toString())
            }
            is PackageFragmentDescriptor -> Pair(parent.fqName.toString(), "")
            else -> error("Doesn't seem to be an exported descriptor: $descriptor")
        }

        val isAccessor = declaration is IrSimpleFunction && declaration.correspondingProperty != null
        val isFakeOverride = declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE
        val isDefaultConstructor = descriptor is ClassConstructorDescriptor && parent is ClassDescriptor && parent.kind == ClassKind.OBJECT
        val isEnumEntry = descriptor is ClassDescriptor && descriptor.kind == ClassKind.ENUM_ENTRY
        val isEnumSpecial = declaration.origin == IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER

        val realDeclaration = if (declaration is IrSimpleFunction && isFakeOverride)
            declaration.resolveFakeOverrideMaybeAbstract()!!
        else
            declaration

        val index = if (isAccessor) {
            declarationTable.indexByValue((realDeclaration as IrSimpleFunction).correspondingProperty!!)
        } else if (isDefaultConstructor || isEnumEntry) {
            -1
        } else {
            declarationTable.indexByValue(realDeclaration)
        }

        //println("### descriptor reference index=${index.toString(16)} descriptor=$descriptor")
        //println("realDeclaration: ${realDeclaration.name} in ${realDeclaration.parent.fqNameSafe.toString()}")

        val proto = KonanIr.DescriptorReference.newBuilder()
            .setPackageFqName(packageFqName)
            .setClassFqName(classFqName)
            .setName(descriptor.name.toString())
            .setUniqId(newUniqId(index))

        if (isFakeOverride) {
            proto.setIsFakeOverride(true)
        }

        if (isAccessor) {
            if (declaration.name.asString().startsWith("<get-"))
                proto.setIsGetter(true)
            else if(declaration.name.asString().startsWith("<set-"))
                proto.setIsSetter(true)
            else
                error("A property accessor which is neither getter nor setter: $descriptor")
        } else if (isDefaultConstructor) {
            proto.setIsDefaultConstructor(true)
        } else if (isEnumEntry) {
            proto.setIsEnumEntry(true)
        } else if (isEnumSpecial) {
            proto.setIsEnumSpecial(true)
        }

        return proto.build()
    }

    fun serializeIrSymbol(symbol: IrSymbol): KonanIr.IrSymbol {

        //println("### serializeIrSymbol: owner is ${symbol.owner} descriptor is ${symbol.descriptor.original}")

        if (symbol.owner !is IrDeclaration) error("Expected IrDeclaration") // TODO: change symbol to be IrSymbolDeclaration?

        val proto =  KonanIr.IrSymbol.newBuilder()
            //.setSymbol(serializeIrSymbol(symbol.symbol))

        serializeDescriptorReference(symbol) ?. let {
            proto.setDescriptorReference(it)
        } ?: println("null descriptor reference")

        val kind = when(symbol) {
            is IrAnonymousInitializerSymbol ->
                KonanIr.IrSymbolKind.ANONYMOUS_INIT_SYMBOL
            is IrClassSymbol ->
                KonanIr.IrSymbolKind.CLASS_SYMBOL
            is IrConstructorSymbol ->
                KonanIr.IrSymbolKind.CONSTRUCTOR_SYMBOL
            is IrTypeParameterSymbol ->
                KonanIr.IrSymbolKind.TYPE_PARAMETER_SYMBOL
            is IrEnumEntrySymbol ->
                KonanIr.IrSymbolKind.ENUM_ENTRY_SYMBOL
            is IrVariableSymbol ->
                KonanIr.IrSymbolKind.VARIABLE_SYMBOL
            is IrValueParameterSymbol ->
                KonanIr.IrSymbolKind.VALUE_PARAMETER_SYMBOL
            is IrSimpleFunctionSymbol ->
                KonanIr.IrSymbolKind.FUNCTION_SYMBOL
            is IrReturnTargetSymbol ->
                KonanIr.IrSymbolKind.RETURN_TARGET_SYMBOL
            is IrFieldSymbol ->
                KonanIr.IrSymbolKind.FIELD_SYMBOL

            else ->
                TODO("Unexpected symbol kind: $symbol")
        }

        proto.kind = kind
        val index = declarationTable.indexByValue(symbol.owner as IrDeclaration) // TODO: change symbol to be IrSymbolDeclaration?
        proto.setUniqId(newUniqId(index))
        //println("### serializeIrSymbol: owner is ${symbol.owner} descriptor is ${symbol.descriptor.original} ; kind=$kind ; index is ${index.toString(16)}")
        val owner = symbol.owner as IrDeclaration
        //println("### owner parent: ${owner.parent}; owner descriptor: ${owner.descriptor} owner parent descriptor: ${(owner.parent as? IrDeclaration)?.descriptor}")

        val result =  proto.build()
        //println("proto.uniqId.index = ${proto.uniqId.index}")
        return result
    }

    /* ------- IrTypes ---------------------------------------------------------- */


    fun serializeIrTypeVariance(variance: Variance)= when(variance) {
        Variance.IN_VARIANCE -> KonanIr.IrTypeVariance.IN
        Variance.OUT_VARIANCE -> KonanIr.IrTypeVariance.OUT
        Variance.INVARIANT -> KonanIr.IrTypeVariance.INV
    }

    fun serializeAnnotations(annotations: List<IrCall>): KonanIr.Annotations {
        val proto = KonanIr.Annotations.newBuilder()
        annotations.forEach {
            proto.addAnnotation(serializeCall(it))
        }
        return proto.build()
    }

    fun serializeIrTypeBase(type: IrType, kotlinType: KonanIr.KotlinType?): KonanIr.IrTypeBase {
        val typeBase = type as IrTypeBase // TODO: get rid of the cast.
        val proto = KonanIr.IrTypeBase.newBuilder()
            //.setKotlinType(kotlinType)
            .setVariance(serializeIrTypeVariance(typeBase.variance))
            .setAnnotations(serializeAnnotations(typeBase.annotations))

        return proto.build()
    }

    fun serializeIrTypeProjection(argument: IrTypeProjection)
        = KonanIr.IrTypeProjection.newBuilder()
            .setVariance(serializeIrTypeVariance(argument.variance))
            .setType(serializeIrType(argument.type))
            .build()

    fun serializeTypeArgument(argument: IrTypeArgument): KonanIr.IrTypeArgument {
        val proto = KonanIr.IrTypeArgument.newBuilder()
        when (argument) {
            is IrStarProjection ->
                proto.star = KonanIr.IrStarProjection.newBuilder().build() // TODO: Do we need a singletone here? Or just an enum?
            is IrTypeProjection ->
                proto.type = serializeIrTypeProjection(argument)
            else -> TODO("Unexpected type argument kind: $argument")
        }
        return proto.build()
    }

    fun serializeSimpleType(type: IrSimpleType, kotlinType: KonanIr.KotlinType?): KonanIr.IrSimpleType {
        val proto = KonanIr.IrSimpleType.newBuilder()
            .setBase(serializeIrTypeBase(type, kotlinType))
            .setClassifier(serializeIrSymbol(type.classifier))
            .setHasQuestionMark(type.hasQuestionMark)
        type.arguments.forEach {
            proto.addArgument(serializeTypeArgument(it))
        }
        return proto.build()
    }

    fun serializeDynamicType(type: IrDynamicType) = KonanIr.IrDynamicType.newBuilder()
        .setBase(serializeIrTypeBase(type, null))
        .build()

    fun serializeErrorType(type: IrErrorType)  = KonanIr.IrErrorType.newBuilder()
        .setBase(serializeIrTypeBase(type, null))
        .build()

    private fun serializeIrType(type: IrType) : KonanIr.IrType {
        logger.log{"### serializing IrType: " + type}
        val kotlinType = null
        val proto = KonanIr.IrType.newBuilder()
        when (type) {
            is IrSimpleType ->
                proto.simple = serializeSimpleType(type, kotlinType)
            is IrDynamicType ->
                proto.dynamic = serializeDynamicType(type)
            is IrErrorType ->
                proto.error = serializeErrorType(type)
            else -> TODO("IrType serialization not implemented yet: $type.")
        }
        return proto.build()
    }

    /* -------------------------------------------------------------------------- */

    private fun serializeBlockBody(expression: IrBlockBody): KonanIr.IrBlockBody {
        val proto = KonanIr.IrBlockBody.newBuilder()
        expression.statements.forEach {
            proto.addStatement(serializeStatement(it))
        }
        return proto.build()
    }

    private fun serializeBranch(branch: IrBranch): KonanIr.IrBranch {
        val proto = KonanIr.IrBranch.newBuilder()

        proto.condition = serializeExpression(branch.condition)
        proto.result = serializeExpression(branch.result)

        return proto.build()
    }

    private fun serializeBlock(block: IrBlock): KonanIr.IrBlock {
        val isLambdaOrigin = 
            block.origin == IrStatementOrigin.LAMBDA ||
            block.origin == IrStatementOrigin.ANONYMOUS_FUNCTION
        val proto = KonanIr.IrBlock.newBuilder()
            .setIsLambdaOrigin(isLambdaOrigin)
        block.statements.forEach {
            proto.addStatement(serializeStatement(it))
        }
        return proto.build()
    }

    private fun serializeComposite(composite: IrComposite): KonanIr.IrComposite {
        val proto = KonanIr.IrComposite.newBuilder()
        composite.statements.forEach {
            proto.addStatement(serializeStatement(it))
        }
        return proto.build()
    }

    private fun serializeCatch(catch: IrCatch): KonanIr.IrCatch {
        val proto = KonanIr.IrCatch.newBuilder()
           .setCatchParameter(serializeDeclaration(catch.catchParameter))
           .setResult(serializeExpression(catch.result))
        return proto.build()
    }

    private fun serializeStringConcat(expression: IrStringConcatenation): KonanIr.IrStringConcat {
        val proto = KonanIr.IrStringConcat.newBuilder()
        expression.arguments.forEach {
            proto.addArgument(serializeExpression(it))
        }
        return proto.build()
    }

    private fun irCallToPrimitiveKind(call: IrCall): KonanIr.IrCall.Primitive = when (call) {
        is IrNullaryPrimitiveImpl 
            -> KonanIr.IrCall.Primitive.NULLARY
        is IrUnaryPrimitiveImpl 
            -> KonanIr.IrCall.Primitive.UNARY
        is IrBinaryPrimitiveImpl 
            -> KonanIr.IrCall.Primitive.BINARY
        else
            -> KonanIr.IrCall.Primitive.NOT_PRIMITIVE
    }

    private fun serializeMemberAccessCommon(call: IrMemberAccessExpression): KonanIr.MemberAccessCommon {
        val proto = KonanIr.MemberAccessCommon.newBuilder()
        if (call.extensionReceiver != null) {
            proto.extensionReceiver = serializeExpression(call.extensionReceiver!!)
        }

        if (call.dispatchReceiver != null)  {
            proto.dispatchReceiver = serializeExpression(call.dispatchReceiver!!)
        }
        proto.typeArguments = serializeTypeArguments(call)

        for (index in 0 .. call.valueArgumentsCount-1) {
            val actual = call.getValueArgument(index)
            val argOrNull = KonanIr.NullableIrExpression.newBuilder()
            if (actual == null) {
                // Am I observing an IR generation regression?
                // I see a lack of arg for an empty vararg,
                // rather than an empty vararg node.

                // TODO: how do we assert that without descriptora?
                //assert(it.varargElementType != null || it.hasDefaultValue())
            } else {
                argOrNull.expression = serializeExpression(actual)
            }
            proto.addValueArgument(argOrNull)
        }
        return proto.build()
    }

    private fun serializeCall(call: IrCall): KonanIr.IrCall {
        val proto = KonanIr.IrCall.newBuilder()

        proto.kind = irCallToPrimitiveKind(call)
        proto.symbol = serializeIrSymbol(call.symbol)

        call.superQualifierSymbol ?. let {
            proto.`super` = serializeIrSymbol(it)
        }
        proto.memberAccess = serializeMemberAccessCommon(call)
        return proto.build()
    }

    private fun serializeFunctionReference(callable: IrFunctionReference): KonanIr.IrFunctionReference {
        val proto = KonanIr.IrFunctionReference.newBuilder()
            .setSymbol(serializeIrSymbol(callable.symbol))
            .setTypeArguments(serializeTypeArguments(callable))
        callable.origin?.let { proto.origin = (it as IrStatementOriginImpl).debugName }
        return proto.build()
    }


    private fun serializePropertyReference(callable: IrPropertyReference): KonanIr.IrPropertyReference {
        val proto = KonanIr.IrPropertyReference.newBuilder()
                //.setDeclaration(newUniqId(declarationTable.indexByValue(property)))
                .setTypeArguments(serializeTypeArguments(callable))
        callable.field?.let { proto.field = serializeIrSymbol(it) }
        callable.getter?.let { proto.getter = serializeIrSymbol(it) }
        callable.setter?.let { proto.setter = serializeIrSymbol(it) }
        callable.origin?.let { proto.origin = (it as IrStatementOriginImpl).debugName }
        return proto.build()
    }

    private fun serializeClassReference(expression: IrClassReference): KonanIr.IrClassReference {
        val proto = KonanIr.IrClassReference.newBuilder()
            .setClassSymbol(serializeIrSymbol(expression.symbol))
            .setType(serializeIrType(expression.type))
        return proto.build()
    }

    private fun serializeConst(value: IrConst<*>): KonanIr.IrConst {
        val proto = KonanIr.IrConst.newBuilder()
        when (value.kind) {
            IrConstKind.Null        -> proto.`null` = true
            IrConstKind.Boolean     -> proto.boolean = value.value as Boolean
            IrConstKind.Byte        -> proto.byte = (value.value as Byte).toInt()
            IrConstKind.Char        -> proto.char = (value.value as Char).toInt()
            IrConstKind.Short       -> proto.short = (value.value as Short).toInt()
            IrConstKind.Int         -> proto.int = value.value as Int
            IrConstKind.Long        -> proto.long = value.value as Long
            IrConstKind.String      -> proto.string = value.value as String
            IrConstKind.Float       -> proto.float = value.value as Float
            IrConstKind.Double      -> proto.double = value.value as Double
         }
        return proto.build()
    }

    private fun serializeDelegatingConstructorCall(call: IrDelegatingConstructorCall): KonanIr.IrDelegatingConstructorCall {
        val proto = KonanIr.IrDelegatingConstructorCall.newBuilder()
            .setSymbol(serializeIrSymbol(call.symbol))
            .setMemberAccess(serializeMemberAccessCommon(call))
        return proto.build()
    }

    private fun serializeDoWhile(expression: IrDoWhileLoop): KonanIr.IrDoWhile {
        val proto = KonanIr.IrDoWhile.newBuilder()
            .setLoop(serializeLoop(expression))

        return proto.build()
    }

    fun serializeEnumConstructorCall(call: IrEnumConstructorCall): KonanIr.IrEnumConstructorCall {
        val proto = KonanIr.IrEnumConstructorCall.newBuilder()
            .setSymbol(serializeIrSymbol(call.symbol))
            .setMemberAccess(serializeMemberAccessCommon(call))
        return proto.build()
    }

    private fun serializeGetClass(expression: IrGetClass): KonanIr.IrGetClass {
        val proto = KonanIr.IrGetClass.newBuilder()
            .setArgument(serializeExpression(expression.argument))
        return proto.build()
    }

    private fun serializeGetEnumValue(expression: IrGetEnumValue): KonanIr.IrGetEnumValue {
        val proto = KonanIr.IrGetEnumValue.newBuilder()
            .setType(serializeIrType(expression.type))
            .setSymbol(serializeIrSymbol(expression.symbol))
        return proto.build()
    }

    private fun serializeFieldAccessCommon(expression: IrFieldAccessExpression): KonanIr.FieldAccessCommon {
        val proto = KonanIr.FieldAccessCommon.newBuilder()
            .setSymbol(serializeIrSymbol(expression.symbol))
        expression.superQualifierSymbol?.let { proto.`super` = serializeIrSymbol(it) }
        expression.receiver?.let { proto.receiver = serializeExpression(it) }
        return proto.build()
    }

    private fun serializeGetField(expression: IrGetField): KonanIr.IrGetField {
        val proto = KonanIr.IrGetField.newBuilder()
            .setFieldAccess(serializeFieldAccessCommon(expression))
            .setType(serializeIrType(expression.type))
        return proto.build()
    }

    private fun serializeGetValue(expression: IrGetValue): KonanIr.IrGetValue {
        val type = (expression as IrGetValueImpl).type
        val proto = KonanIr.IrGetValue.newBuilder()
            .setSymbol(serializeIrSymbol(expression.symbol))
            .setType(serializeIrType(type))
        return proto.build()
    }

    private fun serializeGetObject(expression: IrGetObjectValue): KonanIr.IrGetObject {
        val proto = KonanIr.IrGetObject.newBuilder()
            .setSymbol(serializeIrSymbol(expression.symbol))
        return proto.build()
    }

    private fun serializeInstanceInitializerCall(call: IrInstanceInitializerCall): KonanIr.IrInstanceInitializerCall {
        val proto = KonanIr.IrInstanceInitializerCall.newBuilder()

        proto.symbol = serializeIrSymbol(call.classSymbol)

        return proto.build()
    }

    private fun serializeReturn(expression: IrReturn): KonanIr.IrReturn {
        val proto = KonanIr.IrReturn.newBuilder()
            .setReturnTarget(serializeIrSymbol(expression.returnTargetSymbol))
            .setValue(serializeExpression(expression.value))
        return proto.build()
    }

    private fun serializeSetField(expression: IrSetField): KonanIr.IrSetField {
        val proto = KonanIr.IrSetField.newBuilder()
            .setFieldAccess(serializeFieldAccessCommon(expression))
            .setValue(serializeExpression(expression.value))
        return proto.build()
    }

    private fun serializeSetVariable(expression: IrSetVariable): KonanIr.IrSetVariable {
        val proto = KonanIr.IrSetVariable.newBuilder()
            .setSymbol(serializeIrSymbol(expression.symbol))
            .setValue(serializeExpression(expression.value))
        return proto.build()
    }

    private fun serializeSpreadElement(element: IrSpreadElement): KonanIr.IrSpreadElement {
        val coordinates = serializeCoordinates(element.startOffset, element.endOffset)
        return KonanIr.IrSpreadElement.newBuilder()
            .setExpression(serializeExpression(element.expression))
            .setCoordinates(coordinates)
            .build()
    }

    private fun serializeSyntheticBody(expression: IrSyntheticBody)
            = KonanIr.IrSyntheticBody.newBuilder()
                .setKind(when(expression.kind) {
                    IrSyntheticBodyKind.ENUM_VALUES -> KonanIr.IrSyntheticBodyKind.ENUM_VALUES
                    IrSyntheticBodyKind.ENUM_VALUEOF -> KonanIr.IrSyntheticBodyKind.ENUM_VALUEOF
                }
            )
            .build()

    private fun serializeThrow(expression: IrThrow): KonanIr.IrThrow {
        val proto = KonanIr.IrThrow.newBuilder()
            .setValue(serializeExpression(expression.value))
        return proto.build()
    }

    private fun serializeTry(expression: IrTry): KonanIr.IrTry {
        val proto = KonanIr.IrTry.newBuilder()
            .setResult(serializeExpression(expression.tryResult))
        val catchList = expression.catches
        catchList.forEach {
            proto.addCatch(serializeStatement(it))
        }
        val finallyExpression = expression.finallyExpression
        if (finallyExpression != null) {
            proto.finally = serializeExpression(finallyExpression)
        }
        return proto.build()
    }

    private fun serializeTypeOperator(operator: IrTypeOperator): KonanIr.IrTypeOperator = when (operator) {
        IrTypeOperator.CAST
            -> KonanIr.IrTypeOperator.CAST
        IrTypeOperator.IMPLICIT_CAST
            -> KonanIr.IrTypeOperator.IMPLICIT_CAST
        IrTypeOperator.IMPLICIT_NOTNULL
            -> KonanIr.IrTypeOperator.IMPLICIT_NOTNULL
        IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
            -> KonanIr.IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
        IrTypeOperator.IMPLICIT_INTEGER_COERCION
            -> KonanIr.IrTypeOperator.IMPLICIT_INTEGER_COERCION
        IrTypeOperator.SAFE_CAST
            -> KonanIr.IrTypeOperator.SAFE_CAST
        IrTypeOperator.INSTANCEOF
            -> KonanIr.IrTypeOperator.INSTANCEOF
        IrTypeOperator.NOT_INSTANCEOF
            -> KonanIr.IrTypeOperator.NOT_INSTANCEOF
    }

    private fun serializeTypeOp(expression: IrTypeOperatorCall): KonanIr.IrTypeOp {
        val proto = KonanIr.IrTypeOp.newBuilder()
            .setOperator(serializeTypeOperator(expression.operator))
            .setOperand(serializeIrType(expression.typeOperand))
            .setArgument(serializeExpression(expression.argument))
        return proto.build()

    }

    private fun serializeVararg(expression: IrVararg): KonanIr.IrVararg {
        val proto = KonanIr.IrVararg.newBuilder()
            .setElementType(serializeIrType(expression.varargElementType))
        expression.elements.forEach {
            proto.addElement(serializeVarargElement(it))
        }
        return proto.build()
    }

    private fun serializeVarargElement(element: IrVarargElement): KonanIr.IrVarargElement {
        val proto = KonanIr.IrVarargElement.newBuilder()
        when (element) {
            is IrExpression
                -> proto.expression = serializeExpression(element)
            is IrSpreadElement
                -> proto.spreadElement = serializeSpreadElement(element)
            else -> error("Unknown vararg element kind")
        }
        return proto.build()
    }

    private fun serializeWhen(expression: IrWhen): KonanIr.IrWhen {
        val proto = KonanIr.IrWhen.newBuilder()

        val branches = expression.branches
        branches.forEach {
            proto.addBranch(serializeStatement(it))
        }

        return proto.build()
    }

    private fun serializeLoop(expression: IrLoop): KonanIr.Loop {
        val proto = KonanIr.Loop.newBuilder()
            .setCondition(serializeExpression(expression.condition))
        val label = expression.label
        if (label != null) {
            proto.label = label
        }

        proto.loopId = currentLoopIndex
        loopIndex[expression] = currentLoopIndex++

        val body = expression.body
        if (body != null) {
            proto.body = serializeExpression(body)
        }

        return proto.build()
    }

    private fun serializeWhile(expression: IrWhileLoop): KonanIr.IrWhile {
        val proto = KonanIr.IrWhile.newBuilder()
            .setLoop(serializeLoop(expression))

        return proto.build()
    }

    private fun serializeBreak(expression: IrBreak): KonanIr.IrBreak {
        val proto = KonanIr.IrBreak.newBuilder()
        val label = expression.label
        if (label != null) {
            proto.label = label
        }
        val loopId = loopIndex[expression.loop]!!
        proto.loopId = loopId

        return proto.build()
    }

    private fun serializeContinue(expression: IrContinue): KonanIr.IrContinue {
        val proto = KonanIr.IrContinue.newBuilder()
        val label = expression.label
        if (label != null) {
            proto.label = label
        }
        val loopId = loopIndex[expression.loop]!!
        proto.loopId = loopId

        return proto.build()
    }

    private fun serializeExpression(expression: IrExpression): KonanIr.IrExpression {
        logger.log{"### serializing Expression: ${ir2string(expression)}"}

        val coordinates = serializeCoordinates(expression.startOffset, expression.endOffset)
        val proto = KonanIr.IrExpression.newBuilder()
            .setType(serializeIrType(expression.type))
            .setCoordinates(coordinates)

        val operationProto = KonanIr.IrOperation.newBuilder()
        
        when (expression) {
            is IrBlock       -> operationProto.block = serializeBlock(expression)
            is IrBreak       -> operationProto.`break` = serializeBreak(expression)
            is IrClassReference
                             -> operationProto.classReference = serializeClassReference(expression)
            is IrCall        -> operationProto.call = serializeCall(expression)

            is IrComposite   -> operationProto.composite = serializeComposite(expression)
            is IrConst<*>    -> operationProto.const = serializeConst(expression)
            is IrContinue    -> operationProto.`continue` = serializeContinue(expression)
            is IrDelegatingConstructorCall
                             -> operationProto.delegatingConstructorCall = serializeDelegatingConstructorCall(expression)
            is IrDoWhileLoop -> operationProto.doWhile = serializeDoWhile(expression)
            is IrEnumConstructorCall
                             -> operationProto.enumConstructorCall = serializeEnumConstructorCall(expression)
            is IrFunctionReference
                             -> operationProto.functionReference = serializeFunctionReference(expression)
            is IrGetClass    -> operationProto.getClass = serializeGetClass(expression)
            is IrGetField    -> operationProto.getField = serializeGetField(expression)
            is IrGetValue    -> operationProto.getValue = serializeGetValue(expression)
            is IrGetEnumValue    
                             -> operationProto.getEnumValue = serializeGetEnumValue(expression)
            is IrGetObjectValue    
                             -> operationProto.getObject = serializeGetObject(expression)
            is IrInstanceInitializerCall        
                             -> operationProto.instanceInitializerCall = serializeInstanceInitializerCall(expression)
            is IrPropertyReference
                             -> operationProto.propertyReference = serializePropertyReference(expression)
            is IrReturn      -> operationProto.`return` = serializeReturn(expression)
            is IrSetField    -> operationProto.setField = serializeSetField(expression)
            is IrSetVariable -> operationProto.setVariable = serializeSetVariable(expression)
            is IrStringConcatenation 
                             -> operationProto.stringConcat = serializeStringConcat(expression)
            is IrThrow       -> operationProto.`throw` = serializeThrow(expression)
            is IrTry         -> operationProto.`try` = serializeTry(expression)
            is IrTypeOperatorCall 
                             -> operationProto.typeOp = serializeTypeOp(expression)
            is IrVararg      -> operationProto.vararg = serializeVararg(expression)
            is IrWhen        -> operationProto.`when` = serializeWhen(expression)
            is IrWhileLoop   -> operationProto.`while` = serializeWhile(expression)
            else -> {
                TODO("Expression serialization not implemented yet: ${ir2string(expression)}.")
            }
        }
        proto.setOperation(operationProto)

        return proto.build()
    }

    private fun serializeStatement(statement: IrElement): KonanIr.IrStatement {
        logger.log{"### serializing Statement: ${ir2string(statement)}"}

        val coordinates = serializeCoordinates(statement.startOffset, statement.endOffset)
        val proto = KonanIr.IrStatement.newBuilder()
            .setCoordinates(coordinates)

        when (statement) {
            is IrDeclaration -> { logger.log{" ###Declaration "}; proto.declaration = serializeDeclaration(statement) }
            is IrExpression -> { logger.log{" ###Expression "}; proto.expression = serializeExpression(statement) }
            is IrBlockBody -> { logger.log{" ###BlockBody "}; proto.blockBody = serializeBlockBody(statement) }
            is IrBranch    -> { logger.log{" ###Branch "}; proto.branch = serializeBranch(statement) }
            is IrCatch    -> { logger.log{" ###Catch "}; proto.catch = serializeCatch(statement) }
            is IrSyntheticBody -> { logger.log{" ###SyntheticBody "}; proto.syntheticBody = serializeSyntheticBody(statement) }
            else -> {
                TODO("Statement not implemented yet: ${ir2string(statement)}")
            }
        }
        return proto.build()
    }

    private fun serializeIrTypeAlias(typeAlias: IrTypeAlias) = KonanIr.IrTypeAlias.newBuilder().build()

    private fun serializeIrValueParameter(parameter: IrValueParameter): KonanIr.IrValueParameter {
        val proto = KonanIr.IrValueParameter.newBuilder()
                .setSymbol(serializeIrSymbol(parameter.symbol))
                .setName(parameter.name.toString())
                .setIndex(parameter.index)
                .setType(serializeIrType(parameter.type))
                .setIsCrossinline(parameter.isCrossinline)
                .setIsNoinline(parameter.isNoinline)

        parameter.varargElementType ?. let { proto.setVarargElementType(serializeIrType(it))}
        parameter.defaultValue ?. let { proto.setDefaultValue(serializeExpression(it.expression)) }

        return proto.build()
    }

    private fun serializeIrTypeParameter(parameter: IrTypeParameter): KonanIr.IrTypeParameter {
        val proto =  KonanIr.IrTypeParameter.newBuilder()
                .setSymbol(serializeIrSymbol(parameter.symbol))
                .setName(parameter.name.toString())
                .setIndex(parameter.index)
                .setVariance(serializeIrTypeVariance(parameter.variance))
        parameter.superTypes.forEach {
            proto.addSuperType(serializeIrType(it))
        }
        return proto.build()
    }

    private fun serializeIrTypeParameterContainer(typeParameters: List<IrTypeParameter>): KonanIr.IrTypeParameterContainer {
        val proto = KonanIr.IrTypeParameterContainer.newBuilder()
        typeParameters.forEach {
            proto.addTypeParameter(serializeIrTypeParameter(it))
        }
        return proto.build()
    }

    private fun serializeIrFunctionBase(function: IrFunctionBase): KonanIr.IrFunctionBase {
        val proto = KonanIr.IrFunctionBase.newBuilder()
            .setName(function.name.toString())
            .setVisibility(function.visibility.name)
            .setIsInline(function.isInline)
            .setIsExternal(function.isExternal)
            .setReturnType(serializeIrType(function.returnType))
            .setTypeParameters(serializeIrTypeParameterContainer(function.typeParameters))

        function.dispatchReceiverParameter?.let { proto.setDispatchReceiver(serializeIrValueParameter(it)) }
        function.extensionReceiverParameter?.let { proto.setExtensionReceiver(serializeIrValueParameter(it)) }
        function.valueParameters.forEach {
            proto.addValueParameter(serializeIrValueParameter(it))
        }
        function.body?.let { proto.body = serializeStatement(it) }
        return proto.build()
    }

    private fun serializeModality(modality: Modality) = when (modality) {
        Modality.FINAL -> KonanIr.ModalityKind.FINAL_MODALITY
        Modality.SEALED -> KonanIr.ModalityKind.SEALED_MODALITY
        Modality.OPEN -> KonanIr.ModalityKind.OPEN_MODALITY
        Modality.ABSTRACT -> KonanIr.ModalityKind.ABSTRACT_MODALITY
    }

    private fun serializeIrConstructor(declaration: IrConstructor): KonanIr.IrConstructor {
        return KonanIr.IrConstructor.newBuilder()
            .setSymbol(serializeIrSymbol(declaration.symbol))
            .setBase(serializeIrFunctionBase(declaration as IrFunctionBase))
            .setIsPrimary(declaration.isPrimary)
            .build()
    }

    private fun serializeIrFunction(declaration: IrSimpleFunction): KonanIr.IrFunction {
        val function = declaration// as IrFunctionImpl
        val proto = KonanIr.IrFunction.newBuilder()
            .setSymbol(serializeIrSymbol(function.symbol))
            .setModality(serializeModality(function.modality))
            .setIsTailrec(function.isTailrec)
            .setIsSuspend(function.isSuspend)

        function.overriddenSymbols.forEach {
            proto.addOverridden(serializeIrSymbol(it))
        }

        // TODO!!!
        function.correspondingProperty ?. let {
            val index = declarationTable.indexByValue(it)
            proto.setCorrespondingProperty(newUniqId(index))
        }

        val base = serializeIrFunctionBase(function as IrFunctionBase)
        proto.setBase(base)

        return proto.build()
    }

    private fun serializeIrAnonymousInit(declaration: IrAnonymousInitializer)
        = KonanIr.IrAnonymousInit.newBuilder()
            .setSymbol(serializeIrSymbol(declaration.symbol))
            .setBody(serializeStatement(declaration.body))
            .build()

    private fun serializeVisibility(visibility: Visibility): String {
        return visibility.name
    }

    private fun serializeIrProperty(property: IrProperty): KonanIr.IrProperty {
        println("###serializeIrProperty name=${property.name}")
        val index = declarationTable.indexByValue(property)
        println("###serializeIrProperty index: ${index.toString(16)} ")


        val proto = KonanIr.IrProperty.newBuilder()
            .setIsDelegated(property.isDelegated)
            .setDeclaration(newUniqId(index))
            .setName(property.name.toString())
            .setVisibility(serializeVisibility(property.visibility))
            .setModality(serializeModality(property.modality))
            .setIsVar(property.isVar)
            .setIsConst(property.isConst)
            .setIsLateinit(property.isLateinit)
            .setIsDelegated(property.isDelegated)
            .setIsExternal(property.isExternal)

        val backingField = property.backingField
        val getter = property.getter
        val setter = property.setter
        if (backingField != null)
            proto.backingField = serializeIrField(backingField)
        if (getter != null)
            proto.getter = serializeIrFunction(getter)
        if (setter != null)
            proto.setter = serializeIrFunction(setter)

        return proto.build()
    }

    private fun serializeIrField(field: IrField): KonanIr.IrField {
        val proto = KonanIr.IrField.newBuilder()
            .setSymbol(serializeIrSymbol(field.symbol))
            .setName(field.name.toString())
            .setVisibility(field.visibility.displayName)
            .setIsFinal(field.isFinal)
            .setIsExternal(field.isExternal)
            .setType(serializeIrType(field.type))
        val initializer = field.initializer?.expression
        if (initializer != null) {
            proto.initializer = serializeExpression(initializer)
        }
        return proto.build()
    }

    private fun serializeIrVariable(variable: IrVariable): KonanIr.IrVariable {
        val proto = KonanIr.IrVariable.newBuilder()
            .setSymbol(serializeIrSymbol(variable.symbol))
            .setName(variable.name.toString())
            .setType(serializeIrType(variable.type))
            .setIsConst(variable.isConst)
            .setIsVar(variable.isVar)
            .setIsLateinit(variable.isLateinit)
        variable.initializer ?. let { proto.initializer = serializeExpression(it) }
        return proto.build()
    }

    private fun serializeIrDeclarationContainer(declarations: List<IrDeclaration>): KonanIr.IrDeclarationContainer {
        val proto = KonanIr.IrDeclarationContainer.newBuilder()
        declarations.forEach {
            if (it.origin is IrDeclarationOrigin.FAKE_OVERRIDE) return@forEach
            proto.addDeclaration(serializeDeclaration(it))
        }
        return proto.build()
    }

    private fun serializeClassKind(kind: ClassKind) = when(kind) {
        CLASS -> KonanIr.ClassKind.CLASS
        INTERFACE -> KonanIr.ClassKind.INTERFACE
        ENUM_CLASS -> KonanIr.ClassKind.ENUM_CLASS
        ENUM_ENTRY -> KonanIr.ClassKind.ENUM_ENTRY
        ANNOTATION_CLASS -> KonanIr.ClassKind.ANNOTATION_CLASS
        OBJECT -> KonanIr.ClassKind.OBJECT
    }

    private fun serializeIrClass(clazz: IrClass): KonanIr.IrClass {

        val proto = KonanIr.IrClass.newBuilder()
                .setName(clazz.name.toString())
                .setSymbol(serializeIrSymbol(clazz.symbol))
                .setKind(serializeClassKind(clazz.kind))
                .setVisibility(clazz.visibility.name)
                .setModality(serializeModality(clazz.modality))
                .setIsCompanion(clazz.isCompanion)
                .setIsInner(clazz.isInner)
                .setIsData(clazz.isData)
                .setIsExternal(clazz.isExternal)
                .setTypeParameters(serializeIrTypeParameterContainer(clazz.typeParameters))
                .setDeclarationContainer(serializeIrDeclarationContainer(clazz.declarations))
        clazz.superTypes.forEach {
            proto.addSuperType(serializeIrType(it))
        }
        clazz.thisReceiver?.let { proto.thisReceiver = serializeIrValueParameter(it) }

        return proto.build()
    }

    private fun serializeIrEnumEntry(enumEntry: IrEnumEntry): KonanIr.IrEnumEntry {
        val proto = KonanIr.IrEnumEntry.newBuilder()
            .setName(enumEntry.name.toString())
            .setSymbol(serializeIrSymbol(enumEntry.symbol))

        val initializer = enumEntry.initializerExpression!!
        proto.initializer = serializeExpression(initializer)
        val correspondingClass = enumEntry.correspondingClass
        if (correspondingClass != null) {
            proto.correspondingClass = serializeDeclaration(correspondingClass)
        }
        return proto.build()
    }

    private fun serializeIrDeclarationOrigin(origin: IrDeclarationOrigin) =
            KonanIr.IrDeclarationOrigin.newBuilder()
                    .setName((origin as IrDeclarationOriginImpl).name)

    private fun serializeDeclaration(declaration: IrDeclaration): KonanIr.IrDeclaration {
        logger.log{"### serializing Declaration: ${ir2string(declaration)}"}

/*
        if (descriptor != rootFunction &&
                declaration !is IrVariable) {
            localDeclarationSerializer.pushContext(descriptor)
        }
*/
        /*
        var kotlinDescriptor = serializeIrSymbol(descriptor)
        var realDescriptor: KonanIr.DeclarationDescriptor? = null
        if (descriptor != rootFunction) {
            realDescriptor = localDeclarationSerializer.serializeLocalDeclaration(descriptor)
        }
        */
        val declarator = KonanIr.IrDeclarator.newBuilder()

        when (declaration) {
            is IrTypeAlias
               -> declarator.irTypeAlias = serializeIrTypeAlias(declaration)
            is IrAnonymousInitializer
                -> declarator.irAnonymousInit = serializeIrAnonymousInit(declaration)
            is IrConstructor
                -> declarator.irConstructor = serializeIrConstructor(declaration)
            is IrField
                -> declarator.irField = serializeIrField(declaration)
            is IrSimpleFunction
                -> declarator.irFunction = serializeIrFunction(declaration)
            is IrVariable 
                -> declarator.irVariable = serializeIrVariable(declaration)
            is IrClass
                -> declarator.irClass = serializeIrClass(declaration)
            is IrEnumEntry
                -> declarator.irEnumEntry = serializeIrEnumEntry(declaration)
            is IrProperty
                -> declarator.irProperty = serializeIrProperty(declaration)
            else
                -> TODO("Declaration serialization not supported yet: $declaration")
        }
/*
        if (declaration !is IrVariable) {
            localDeclarationSerializer.popContext()
        }
*/
 /*
        if (descriptor != rootFunction) {
            val localDeclaration = KonanIr.LocalDeclaration
                .newBuilder()
                .setSymbol(realDescriptor!!)
                .build()
            kotlinDescriptor = kotlinDescriptor
                .toBuilder()
                .setIrLocalDeclaration(localDeclaration)
                .build()
        }
*/
        val coordinates = serializeCoordinates(declaration.startOffset, declaration.endOffset)
        val annotations = serializeAnnotations(declaration.annotations)
        val origin = serializeIrDeclarationOrigin(declaration.origin)
        val proto = KonanIr.IrDeclaration.newBuilder()
            //.setKind(declaration.irKind())
            //.setSymbol(kotlinDescriptor)
            .setCoordinates(coordinates)
            .setAnnotations(annotations)
            .setOrigin(origin)


        proto.setDeclarator(declarator)

        // TODO disabled for now.
        //val fileName = context.ir.originalModuleIndex.declarationToFile[declaration.descriptor]
        //proto.fileName = fileName

        proto.fileName = "some file name"

        return proto.build()
    }

    private fun encodeDeclaration(declaration: IrDeclaration): String {
        val proto = serializeDeclaration(declaration)
        val byteArray = proto.toByteArray()
        return base64Encode(byteArray)
    }





// ---------- Top level ------------------------------------------------------

    fun serializeFileEntry(entry: SourceManager.FileEntry)
        = KonanIr.FileEntry.newBuilder()
            .setName(entry.name)
            .build()

    val topLevelDeclarations = mutableMapOf<Long, ByteArray>()

    fun serializeIrFile(file: IrFile): KonanIr.IrFile {
        val proto = KonanIr.IrFile.newBuilder()
                .setFileEntry(serializeFileEntry(file.fileEntry))
                .setFqName(file.fqName.toString())

        file.declarations.forEach {
            if (it is IrTypeAlias) return@forEach

            val byteArray = serializeDeclaration(it).toByteArray()
            val index = declarationTable.indexByValue(it)
            topLevelDeclarations.put(index, byteArray)
            proto.addDeclarationId(newUniqId(index))
        }
        return proto.build()
    }

    fun serializeModule(module: IrModuleFragment): KonanIr.IrModule {
        val proto = KonanIr.IrModule.newBuilder()
                .setName(module.name.toString())
        module.files.forEach {
            proto.addFile(serializeIrFile(it))
        }
        return proto.build()
    }

    //fun serializedModule(module: IrModuleFragment): ByteArray {
     //   return serializeModule(module).toByteArray()
    //}

    fun serializedIrModule(module: IrModuleFragment): SerializedIr {
        val moduleHeader = serializeModule(module).toByteArray()
        return SerializedIr(moduleHeader, topLevelDeclarations)

    }


}





// --------- Deserializer part -----------------------------

public class IrModuleDeserialization(val logger: WithLogger, val builtIns: IrBuiltIns/*, val symbolTable: SymbolTable*//*,
                              private val rootFunction: FunctionDescriptor*/): IrDeserializer {

    var currentModule: ModuleDescriptor? = null

    private val loopIndex = mutableMapOf<Int, IrLoop>()

    val originIndex = (IrDeclarationOrigin::class.nestedClasses).map { it.objectInstance as IrDeclarationOriginImpl }.associateBy { it.name }

/*
    private val rootMember = rootFunction.deserializedPropertyIfAccessor
    private val localDeserializer = LocalDeclarationDeserializer(rootMember)

    private val descriptorDeserializer = IrDescriptorDeserializer(
        context, rootMember, localDeserializer)

    private fun deserializeKotlinType(proto: KonanIr.KotlinType)
        = descriptorDeserializer.deserializeKotlinType(proto)
*/

    private fun deserializeTypeArguments(proto: KonanIr.TypeArguments): List<IrType> {
        logger.log{"### deserializeTypeArguments"}
        val result = mutableListOf<IrType>()
        proto.typeArgumentList.forEach { typeProto ->
            val type = deserializeIrType(typeProto)
            result.add(type)
            logger.log{"$type"}
        }
        return result
    }

    /* ----- IrTypes ------------------------------------------------ */

    val errorClassDescriptor = ErrorUtils.createErrorClass("the descriptor should not be needed")
    val dummyFunctionDescriptor = builtIns.builtIns.any.unsubstitutedMemberScope.getContributedDescriptors().first { it is FunctionDescriptor} as FunctionDescriptor

    val dummyConstructorDescriptor = builtIns.builtIns.any.getConstructors().first()

    val dummyPropertyDescriptor = builtIns.builtIns.string.unsubstitutedMemberScope.getContributedDescriptors().first { it is PropertyDescriptor} as PropertyDescriptor


    val dummyVariableDescriptor = IrTemporaryVariableDescriptorImpl(
            errorClassDescriptor,
            Name.identifier("the descriptor should not be needed"),
            builtIns.builtIns.unitType)

    val dummyParameterDescriptor = ValueParameterDescriptorImpl(
            dummyFunctionDescriptor,
            null,
            0,
            Annotations.EMPTY,
            Name.identifier("the descriptor should not be needed"),
            builtIns.builtIns.unitType,
            false,
            false,
            false,
            null,
            SourceElement.NO_SOURCE)

    val dummyTypeParameterDescriptor = TypeParameterDescriptorImpl.createWithDefaultBound(
            errorClassDescriptor,
            Annotations.EMPTY,
            false,
            Variance.INVARIANT,
            Name.identifier("the descriptor should not be needed"),
            0)

    val deserializedSymbols = mutableMapOf<Long, IrSymbol>()
    val deserializedDeclarations = mutableMapOf<DeclarationDescriptor, IrDeclaration>()

    init {
        var currentIndex = 0L
        builtIns.knownBuiltins.forEach {
            deserializedSymbols.put(currentIndex, it.symbol)
            deserializedDeclarations.put(it.descriptor, it)
            currentIndex++
        }
    }

    fun deserializeDescriptorReference(proto: KonanIr.DescriptorReference): DeclarationDescriptor {
        val packageFqName = FqName(proto.packageFqName)
        val classFqName = FqName(proto.classFqName)
        val protoIndex = proto.uniqId.index
        //println("deserializeDescriptorReference: looking for ${protoIndex.toString(16)}")
        //if (proto.isFakeOverride) println("isFakeOverride")
        //if (proto.isGetter) println("isGetter")
        //if (proto.isSetter) println("isSetter")

        val (clazz, members) = if (proto.classFqName == "") {
            Pair(null, currentModule!!.getPackage(packageFqName).memberScope.getContributedDescriptors())
        } else {
            val clazz = currentModule!!.findClassAcrossModuleDependencies(ClassId(packageFqName, classFqName, false))!!
            Pair(clazz, clazz.unsubstitutedMemberScope.getContributedDescriptors() + clazz.getConstructors())
        }

        if (proto.isEnumEntry) {
            val name = proto.name
            //println("trying name: $name")
            val clazz = clazz!! as DeserializedClassDescriptor
            val memberScope = clazz.getUnsubstitutedMemberScope()
            return memberScope.getContributedClassifier(Name.identifier(name), NoLookupLocation.FROM_BACKEND)!!
        }

        if (proto.isEnumSpecial) {
            val name = proto.name
            //println("members in static scope")

            return clazz!!.getStaticScope().getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND)!!.single()

        }

        //println("### members of ${proto.packageFqName} ${proto.classFqName}")
        members.forEach { member ->
            //println(member)
            if (proto.isDefaultConstructor && member is ClassConstructorDescriptor) return member

            val realMembers = if (proto.isFakeOverride && member is CallableMemberDescriptor && member.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                member.resolveFakeOverrideMaybeAbstract()!!
            else
                setOf(member)

            //println("realMembers: ")
            realMembers.forEach{
                //println("${it.name.asString()} in ${clazz?.name?.asString()}")
            }

            val memberIndices = realMembers.map { it.getUniqId()?.index }.filterNotNull()

            //println("Member indices: ")
            memberIndices.forEach {
                //println(it.toString(16))
            }

            if (memberIndices.contains(protoIndex)) {

                //println("Found member with matching index: member = $member")


                if (member is PropertyDescriptor) {
                    if (proto.isSetter) return member.setter!!// ?: return@forEach
                    if (proto.isGetter) return member.getter!!
                    return member
                } else {
                    return member
                }

            }
        }
        error("Could not find serialized descriptor for index: ${proto.uniqId.index.toString(16)} ")
    }

    fun deserializeIrSymbol(proto: KonanIr.IrSymbol): IrSymbol {
        val index = proto.uniqId.index

        val symbol = deserializedSymbols.getOrPut(index) {

            println("### deserializing IrSymbol:  kind = ${proto.kind} ; index is ${index.toString(16)}")

            val descriptor = if (proto.hasDescriptorReference()) deserializeDescriptorReference(proto.descriptorReference) else null

            println("descriptor = $descriptor")
            println("index = $index")

            when (proto.kind) {
                KonanIr.IrSymbolKind.ANONYMOUS_INIT_SYMBOL ->
                    IrAnonymousInitializerSymbolImpl(descriptor as ClassDescriptor? ?: errorClassDescriptor)
                KonanIr.IrSymbolKind.CLASS_SYMBOL ->
                    IrClassSymbolImpl(descriptor as ClassDescriptor? ?: errorClassDescriptor)
                KonanIr.IrSymbolKind.CONSTRUCTOR_SYMBOL ->
                    IrConstructorSymbolImpl(descriptor as ClassConstructorDescriptor? ?: dummyConstructorDescriptor)
                KonanIr.IrSymbolKind.TYPE_PARAMETER_SYMBOL ->
                    IrTypeParameterSymbolImpl(descriptor as TypeParameterDescriptor? ?: dummyTypeParameterDescriptor)
                KonanIr.IrSymbolKind.ENUM_ENTRY_SYMBOL ->
                    IrEnumEntrySymbolImpl(descriptor as ClassDescriptor? ?: errorClassDescriptor)
                KonanIr.IrSymbolKind.FIELD_SYMBOL ->
                    IrFieldSymbolImpl(descriptor as PropertyDescriptor? ?: dummyPropertyDescriptor)
                KonanIr.IrSymbolKind.FUNCTION_SYMBOL ->
                    IrSimpleFunctionSymbolImpl(descriptor as FunctionDescriptor? ?: dummyFunctionDescriptor)
            //RETURN_TARGET ->
            //  IrReturnTargetSymbolImpl
                KonanIr.IrSymbolKind.VARIABLE_SYMBOL ->
                    IrVariableSymbolImpl(descriptor as VariableDescriptor? ?: dummyVariableDescriptor)
                KonanIr.IrSymbolKind.VALUE_PARAMETER_SYMBOL ->
                    IrValueParameterSymbolImpl(descriptor as ParameterDescriptor? ?: dummyParameterDescriptor)
                else -> TODO("Unexpected classifier symbol kind: ${proto.kind}")
            }
        }
        return symbol

    }

    /* ----- IrSymbols ---------------------------------------------- */


    fun deserializeIrTypeVariance(variance: KonanIr.IrTypeVariance) = when(variance) {
        KonanIr.IrTypeVariance.IN -> Variance.IN_VARIANCE
        KonanIr.IrTypeVariance.OUT -> Variance.OUT_VARIANCE
        KonanIr.IrTypeVariance.INV -> Variance.INVARIANT
    }

    fun deserializeIrTypeArgument(proto: KonanIr.IrTypeArgument) = when (proto.kindCase) {
        STAR -> IrStarProjectionImpl
        TYPE -> makeTypeProjection(
                        deserializeIrType(proto.type.type), deserializeIrTypeVariance(proto.type.variance))
        else -> TODO("Unexpected projection kind")

    }

    fun deserializeAnnotations(annotations: KonanIr.Annotations): List<IrCall> {
        return annotations.annotationList.map {
            deserializeCall(it, 0, 0, builtIns.unitType) // TODO: need a proper deserialization here
        }
    }

    fun deserializeSimpleType(proto: KonanIr.IrSimpleType): IrSimpleType {
        val arguments = proto.argumentList.map { deserializeIrTypeArgument(it) }
        val annotations= deserializeAnnotations(proto.base.annotations)
        //val kotlinType = deserializeKotlinType(proto.base.kotlinType)
        val symbol =  deserializeIrSymbol(proto.classifier) as IrClassifierSymbol
        logger.log { "deserializeSimpleType: symbol=$symbol" }
        val result =  IrSimpleTypeImpl(
                null,
                symbol,
                proto.hasQuestionMark,
                arguments,
                annotations
        )
        logger.log { "ir_type = $result; render = ${result.render()}"}
        return result
    }

    fun deserializeDynamicType(proto: KonanIr.IrDynamicType): IrDynamicType {
        val annotations= deserializeAnnotations(proto.base.annotations)
        val variance = deserializeIrTypeVariance(proto.base.variance)
        return IrDynamicTypeImpl(null, annotations, variance)
    }

    fun deserializeErrorType(proto: KonanIr.IrErrorType): IrErrorType {
        val annotations= deserializeAnnotations(proto.base.annotations)
        val variance = deserializeIrTypeVariance(proto.base.variance)
        return IrErrorTypeImpl(null, annotations, variance)
    }

    fun deserializeIrType(proto: KonanIr.IrType): IrType {
        return when (proto.kindCase) {
            SIMPLE -> deserializeSimpleType(proto.simple)
            DYNAMIC -> deserializeDynamicType(proto.dynamic)
            ERROR -> deserializeErrorType(proto.error)
            else -> TODO("Unexpected IrType kind: ${proto.kindCase}")
        }
    }

    /* -------------------------------------------------------------- */

    private fun deserializeBlockBody(proto: KonanIr.IrBlockBody,
                                     start: Int, end: Int): IrBlockBody {

        val statements = mutableListOf<IrStatement>()

        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }

        return IrBlockBodyImpl(start, end, statements)
    }

    private fun deserializeBranch(proto: KonanIr.IrBranch, start: Int, end: Int): IrBranch {

        val condition = deserializeExpression(proto.condition)
        val result = deserializeExpression(proto.result)

        return IrBranchImpl(start, end, condition, result)
    }

    private fun deserializeCatch(proto: KonanIr.IrCatch, start: Int, end: Int): IrCatch {
        val catchParameter = deserializeDeclaration(proto.catchParameter, null) as IrVariable // TODO: we need a proper parent here
        val result = deserializeExpression(proto.result)

        val catch = IrCatchImpl(start, end, catchParameter, result)
        return catch
    }

    private fun deserializeSyntheticBody(proto: KonanIr.IrSyntheticBody, start: Int, end: Int): IrSyntheticBody {
        val kind = when (proto.kind) {
            KonanIr.IrSyntheticBodyKind.ENUM_VALUES -> IrSyntheticBodyKind.ENUM_VALUES
            KonanIr.IrSyntheticBodyKind.ENUM_VALUEOF -> IrSyntheticBodyKind.ENUM_VALUEOF
        }
        return IrSyntheticBodyImpl(start, end, kind)
    }

    private fun deserializeStatement(proto: KonanIr.IrStatement): IrElement {
        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val element = when (proto.statementCase) {
            StatementCase.BLOCK_BODY //proto.hasBlockBody()
                -> deserializeBlockBody(proto.blockBody, start, end)
            StatementCase.BRANCH //proto.hasBranch()
                -> deserializeBranch(proto.branch, start, end)
            StatementCase.CATCH //proto.hasCatch()
                -> deserializeCatch(proto.catch, start, end)
            StatementCase.DECLARATION // proto.hasDeclaration()
                -> deserializeDeclaration(proto.declaration, null) // TODO: we need a proper parent here.
            StatementCase.EXPRESSION // proto.hasExpression()
                -> deserializeExpression(proto.expression)
            StatementCase.SYNTHETIC_BODY // proto.hasSyntheticBody()
                -> deserializeSyntheticBody(proto.syntheticBody, start, end)
            else
                -> TODO("Statement deserialization not implemented: ${proto.statementCase}")
        }

        logger.log{"### Deserialized statement: ${ir2string(element)}"}

        return element
    }

//    private val KotlinType.ir: IrType get() = /*context.ir*/symbolTable.translateErased(this)
//    private val KotlinType.brokenIr: IrType get() = context.ir.translateBroken(this)


    private fun deserializeBlock(proto: KonanIr.IrBlock, start: Int, end: Int, type: IrType): IrBlock {
        val statements = mutableListOf<IrStatement>()
        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }

        val isLambdaOrigin = if (proto.isLambdaOrigin) IrStatementOrigin.LAMBDA else null

        return IrBlockImpl(start, end, type, isLambdaOrigin, statements)
    }

    private fun deserializeMemberAccessCommon(access: IrMemberAccessExpression, proto: KonanIr.MemberAccessCommon) {

        println("valueArgumentsList.size = ${ proto.valueArgumentList.size}")
        proto.valueArgumentList.mapIndexed { i, arg ->
            /*
            val exprOrNull = if (arg.hasExpression())
                deserializeExpression(arg.expression)
            else null
            access.putValueArgument(i, exprOrNull)
            */
            println("index = $i")
            if (arg.hasExpression()) {
                val expr = deserializeExpression(arg.expression)
                access.putValueArgument(i, expr)
            }
        }

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, type ->
            access.putTypeArgument(index, type)
        }

        if (proto.hasDispatchReceiver()) {
            access.dispatchReceiver = deserializeExpression(proto.dispatchReceiver)
        }
        if (proto.hasExtensionReceiver()) {
            access.extensionReceiver = deserializeExpression(proto.extensionReceiver)
        }
    }

    private fun deserializeClassReference(proto: KonanIr.IrClassReference, start: Int, end: Int, type: IrType): IrClassReference {
        val symbol = deserializeIrSymbol(proto.classSymbol) as IrClassifierSymbol
        val classType = deserializeIrType(proto.type)
        /** TODO: [createClassifierSymbolForClassReference] is internal function */
        return IrClassReferenceImpl(start, end, type, symbol, classType)
    }

    private fun deserializeCall(proto: KonanIr.IrCall, start: Int, end: Int, type: IrType): IrCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrFunctionSymbol

        val superSymbol = if (proto.hasSuper()) {
            deserializeIrSymbol(proto.`super`) as IrClassSymbol
        } else null

        val call: IrCall = when (proto.kind) {
            KonanIr.IrCall.Primitive.NOT_PRIMITIVE ->
                // TODO: implement the last three args here.
                IrCallImpl(start, end, type,
                            symbol, symbol.descriptor,
                            proto.memberAccess.valueArgumentList.size,
                            proto.memberAccess.typeArguments.typeArgumentCount,
                        null, superSymbol)
            KonanIr.IrCall.Primitive.NULLARY ->
                IrNullaryPrimitiveImpl(start, end, type, null, symbol)
            KonanIr.IrCall.Primitive.UNARY ->
                IrUnaryPrimitiveImpl(start, end, type, null, symbol)
            KonanIr.IrCall.Primitive.BINARY ->
                IrBinaryPrimitiveImpl(start, end, type, null, symbol)
            else -> TODO("Unexpected primitive IrCall.")
        }
        deserializeMemberAccessCommon(call, proto.memberAccess)
        return call
    }

    private fun deserializeComposite(proto: KonanIr.IrComposite, start: Int, end: Int, type: IrType): IrComposite {
        val statements = mutableListOf<IrStatement>()
        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }
        return IrCompositeImpl(start, end, type, null, statements)
    }

    private fun deserializeDelegatingConstructorCall(proto: KonanIr.IrDelegatingConstructorCall, start: Int, end: Int): IrDelegatingConstructorCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        val call = IrDelegatingConstructorCallImpl(start, end, builtIns.unitType, symbol, symbol.descriptor, proto.memberAccess.typeArguments.typeArgumentCount)

        deserializeMemberAccessCommon(call, proto.memberAccess)
        return call
    }



    fun deserializeEnumConstructorCall(proto: KonanIr.IrEnumConstructorCall, start: Int, end: Int, type: IrType): IrEnumConstructorCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        return IrEnumConstructorCallImpl(start, end, type, symbol, proto.memberAccess.typeArguments.typeArgumentList.size, proto.memberAccess.valueArgumentList.size)
    }



    private fun deserializeFunctionReference(proto: KonanIr.IrFunctionReference,
                                             start: Int, end: Int, type: IrType): IrFunctionReference {

        val symbol = deserializeIrSymbol(proto.symbol) as IrFunctionSymbol
        val callable= IrFunctionReferenceImpl(start, end, type, symbol, symbol.descriptor, proto.typeArguments.typeArgumentCount, null)

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, argType ->
            callable.putTypeArgument(index, argType)
        }
        return callable
    }

    private fun deserializeGetClass(proto: KonanIr.IrGetClass, start: Int, end: Int, type: IrType): IrGetClass {
        val argument = deserializeExpression(proto.argument)
        return IrGetClassImpl(start, end, type, argument)
    }

    private fun deserializeGetField(proto: KonanIr.IrGetField, start: Int, end: Int): IrGetField {
        val access = proto.fieldAccess
        val symbol = deserializeIrSymbol(access.symbol) as IrFieldSymbol
        val type = deserializeIrType(proto.type)
        val superQualifier = if (access.hasSuper()) {
            deserializeIrSymbol(access.symbol) as IrClassSymbol
        } else null
        val receiver = if (access.hasReceiver()) {
            deserializeExpression(access.receiver)
        } else null

        return IrGetFieldImpl(start, end, symbol, type, receiver, null, superQualifier)
    }

    private fun deserializeGetValue(proto: KonanIr.IrGetValue, start: Int, end: Int): IrGetValue {
        val symbol = deserializeIrSymbol(proto.symbol) as IrValueSymbol
        val type = deserializeIrType(proto.type)

        // TODO: origin!
        return IrGetValueImpl(start, end, type, symbol, null)
    }

    private fun deserializeGetEnumValue(proto: KonanIr.IrGetEnumValue, start: Int, end: Int): IrGetEnumValue {
        val type = deserializeIrType(proto.type)
        val symbol = deserializeIrSymbol(proto.symbol) as IrEnumEntrySymbol

        return IrGetEnumValueImpl(start, end, type, symbol)
    }

    private fun deserializeGetObject(proto: KonanIr.IrGetObject, start: Int, end: Int, type: IrType): IrGetObjectValue {
        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol
        return IrGetObjectValueImpl(start, end, type, symbol)
    }

    private fun deserializeInstanceInitializerCall(proto: KonanIr.IrInstanceInitializerCall, start: Int, end: Int): IrInstanceInitializerCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol
        return IrInstanceInitializerCallImpl(start, end, symbol, builtIns.unitType)
    }

    private fun deserializePropertyReference(proto: KonanIr.IrPropertyReference,
                                             start: Int, end: Int, type: IrType): IrPropertyReference {

        val field = if (proto.hasField()) deserializeIrSymbol(proto.field) as IrFieldSymbol else null
        val getter = if (proto.hasGetter()) deserializeIrSymbol(proto.getter) as IrFunctionSymbol else null
        val setter = if (proto.hasSetter()) deserializeIrSymbol(proto.setter) as IrFunctionSymbol else null
        //val descriptor = declarationTable.valueByIndex(proto.declaration.index)!!.descriptor as PropertyDescriptor

        val callable= IrPropertyReferenceImpl(start, end, type,
                dummyPropertyDescriptor,
                proto.typeArguments.typeArgumentCount,
                field,
                getter,
                setter,
                null)

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, argType ->
            callable.putTypeArgument(index, argType)
        }
        return callable
    }

    private fun deserializeReturn(proto: KonanIr.IrReturn, start: Int, end: Int, type: IrType): IrReturn {
        val symbol = deserializeIrSymbol(proto.returnTarget) as IrReturnTargetSymbol
        val value = deserializeExpression(proto.value)
        return IrReturnImpl(start, end, builtIns.nothingType, symbol, value)
    }

    private fun deserializeSetField(proto: KonanIr.IrSetField, start: Int, end: Int): IrSetField {
        val access = proto.fieldAccess
        val symbol = deserializeIrSymbol(access.symbol) as IrFieldSymbol
        val superQualifier = if (access.hasSuper()) {
            deserializeIrSymbol(access.symbol) as IrClassSymbol
        } else null
        val receiver = if (access.hasReceiver()) {
            deserializeExpression(access.receiver)
        } else null
        val value = deserializeExpression(proto.value)

        return IrSetFieldImpl(start, end, symbol, receiver, value, builtIns.unitType, null, superQualifier)
    }

    private fun deserializeSetVariable(proto: KonanIr.IrSetVariable, start: Int, end: Int): IrSetVariable {
        val symbol = deserializeIrSymbol(proto.symbol) as IrVariableSymbol
        val value = deserializeExpression(proto.value)
        return IrSetVariableImpl(start, end, builtIns.unitType, symbol, value, null)
    }

    private fun deserializeSpreadElement(proto: KonanIr.IrSpreadElement): IrSpreadElement {
        val expression = deserializeExpression(proto.expression)
        return IrSpreadElementImpl(proto.coordinates.startOffset, proto.coordinates.endOffset, expression)
    }

    private fun deserializeStringConcat(proto: KonanIr.IrStringConcat, start: Int, end: Int, type: IrType): IrStringConcatenation {
        val argumentProtos = proto.argumentList
        val arguments = mutableListOf<IrExpression>()

        argumentProtos.forEach {
            arguments.add(deserializeExpression(it))
        }
        return IrStringConcatenationImpl(start, end, type, arguments)
    }

    private fun deserializeThrow(proto: KonanIr.IrThrow, start: Int, end: Int, type: IrType): IrThrowImpl {
        return IrThrowImpl(start, end, builtIns.nothingType, deserializeExpression(proto.value))
    }

    private fun deserializeTry(proto: KonanIr.IrTry, start: Int, end: Int, type: IrType): IrTryImpl {
        val result = deserializeExpression(proto.result)
        val catches = mutableListOf<IrCatch>()
        proto.catchList.forEach {
            catches.add(deserializeStatement(it) as IrCatch) 
        }
        val finallyExpression = 
            if (proto.hasFinally()) deserializeExpression(proto.getFinally()) else null
        return IrTryImpl(start, end, type, result, catches, finallyExpression)
    }

    private fun deserializeTypeOperator(operator: KonanIr.IrTypeOperator): IrTypeOperator {
        when (operator) {
            KonanIr.IrTypeOperator.CAST
                -> return IrTypeOperator.CAST
            KonanIr.IrTypeOperator.IMPLICIT_CAST
                -> return IrTypeOperator.IMPLICIT_CAST
            KonanIr.IrTypeOperator.IMPLICIT_NOTNULL
                -> return IrTypeOperator.IMPLICIT_NOTNULL
            KonanIr.IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
                -> return IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
            KonanIr.IrTypeOperator.IMPLICIT_INTEGER_COERCION
                -> return IrTypeOperator.IMPLICIT_INTEGER_COERCION
            KonanIr.IrTypeOperator.SAFE_CAST
                -> return IrTypeOperator.SAFE_CAST
            KonanIr.IrTypeOperator.INSTANCEOF
                -> return IrTypeOperator.INSTANCEOF
            KonanIr.IrTypeOperator.NOT_INSTANCEOF
                -> return IrTypeOperator.NOT_INSTANCEOF
        }
    }

    private fun deserializeTypeOp(proto: KonanIr.IrTypeOp, start: Int, end: Int, type: IrType) : IrTypeOperatorCall {
        val operator = deserializeTypeOperator(proto.operator)
        val operand = deserializeIrType(proto.operand)//.brokenIr
        val argument = deserializeExpression(proto.argument)
        return IrTypeOperatorCallImpl(start, end, type, operator, operand).apply {
            this.argument = argument
            this.typeOperandClassifier = operand.classifierOrFail
        }
    }

    private fun deserializeVararg(proto: KonanIr.IrVararg, start: Int, end: Int, type: IrType): IrVararg {
        val elementType = deserializeIrType(proto.elementType)

        val elements = mutableListOf<IrVarargElement>()
        proto.elementList.forEach {
            elements.add(deserializeVarargElement(it))
        }
        return IrVarargImpl(start, end, type, elementType, elements)
    }

    private fun deserializeVarargElement(element: KonanIr.IrVarargElement): IrVarargElement {
        return when (element.varargElementCase) {
            VarargElementCase.EXPRESSION
                -> deserializeExpression(element.expression)
            VarargElementCase.SPREAD_ELEMENT
                -> deserializeSpreadElement(element.spreadElement)
            else 
                -> TODO("Unexpected vararg element")
        }
    }

    private fun deserializeWhen(proto: KonanIr.IrWhen, start: Int, end: Int, type: IrType): IrWhen {
        val branches = mutableListOf<IrBranch>()

        proto.branchList.forEach {
            branches.add(deserializeStatement(it) as IrBranch)
        }

        // TODO: provide some origin!
        return  IrWhenImpl(start, end, type, null, branches)
    }

    private fun deserializeLoop(proto: KonanIr.Loop, loop: IrLoopBase): IrLoopBase {
        val loopId = proto.loopId
        loopIndex.getOrPut(loopId){loop}

        val label = if (proto.hasLabel()) proto.label else null
        val body = if (proto.hasBody()) deserializeExpression(proto.body) else null
        val condition = deserializeExpression(proto.condition)

        loop.label = label
        loop.condition = condition
        loop.body = body

        return loop
    }

    private fun deserializeDoWhile(proto: KonanIr.IrDoWhile, start: Int, end: Int, type: IrType): IrDoWhileLoop {
        // we create the loop before deserializing the body, so that 
        // IrBreak statements have something to put into 'loop' field.
        val loop = IrDoWhileLoopImpl(start, end, type, null)
        deserializeLoop(proto.loop, loop)
        return loop
    }

    private fun deserializeWhile(proto: KonanIr.IrWhile, start: Int, end: Int, type: IrType): IrWhileLoop {
        // we create the loop before deserializing the body, so that 
        // IrBreak statements have something to put into 'loop' field.
        val loop = IrWhileLoopImpl(start, end, type, null)
        deserializeLoop(proto.loop, loop)
        return loop
    }

    private fun deserializeBreak(proto: KonanIr.IrBreak, start: Int, end: Int, type: IrType): IrBreak {
        val label = if(proto.hasLabel()) proto.label else null
        val loopId = proto.loopId
        val loop = loopIndex[loopId]!!
        val irBreak = IrBreakImpl(start, end, type, loop)
        irBreak.label = label

        return irBreak
    }

    private fun deserializeContinue(proto: KonanIr.IrContinue, start: Int, end: Int, type: IrType): IrContinue {
        val label = if(proto.hasLabel()) proto.label else null
        val loopId = proto.loopId
        val loop = loopIndex[loopId]!!
        val irContinue = IrContinueImpl(start, end, type, loop)
        irContinue.label = label

        return irContinue
    }

    private fun deserializeConst(proto: KonanIr.IrConst, start: Int, end: Int, type: IrType): IrExpression =
        when(proto.valueCase) {
            NULL
                -> IrConstImpl.constNull(start, end, type)
            BOOLEAN
                -> IrConstImpl.boolean(start, end, type, proto.boolean)
            BYTE
                -> IrConstImpl.byte(start, end, type, proto.byte.toByte())
            CHAR
                -> IrConstImpl.char(start, end, type, proto.char.toChar())
            SHORT
                -> IrConstImpl.short(start, end, type, proto.short.toShort())
            INT
                -> IrConstImpl.int(start, end, type, proto.int)
            LONG
                -> IrConstImpl.long(start, end, type, proto.long)
            STRING
                -> IrConstImpl.string(start, end, type, proto.string)
            FLOAT
                -> IrConstImpl.float(start, end, type, proto.float)
            DOUBLE
                -> IrConstImpl.double(start, end, type, proto.double)
            VALUE_NOT_SET
                -> error("Const deserialization error: ${proto.valueCase} ")
        }

    private fun deserializeOperation(proto: KonanIr.IrOperation, start: Int, end: Int, type: IrType): IrExpression =
        when (proto.operationCase) {
            BLOCK
                -> deserializeBlock(proto.block, start, end, type)
            BREAK
                -> deserializeBreak(proto.`break`, start, end, type)
            CLASS_REFERENCE
                -> deserializeClassReference(proto.classReference, start, end, type)
            CALL
                -> deserializeCall(proto.call, start, end, type)
            COMPOSITE
                -> deserializeComposite(proto.composite, start, end, type)
            CONST
                -> deserializeConst(proto.const, start, end, type)
            CONTINUE
                -> deserializeContinue(proto.`continue`, start, end, type)
            DELEGATING_CONSTRUCTOR_CALL
                -> deserializeDelegatingConstructorCall(proto.delegatingConstructorCall, start, end)
            DO_WHILE
                -> deserializeDoWhile(proto.doWhile, start, end, type)
            ENUM_CONSTRUCTOR_CALL
                -> deserializeEnumConstructorCall(proto.enumConstructorCall, start, end, type)
            FUNCTION_REFERENCE
                -> deserializeFunctionReference(proto.functionReference, start, end, type)
            GET_ENUM_VALUE
                -> deserializeGetEnumValue(proto.getEnumValue, start, end)
            GET_CLASS
                -> deserializeGetClass(proto.getClass, start, end, type)
            GET_FIELD
                -> deserializeGetField(proto.getField, start, end)
            GET_OBJECT
                -> deserializeGetObject(proto.getObject, start, end, type)
            GET_VALUE
                -> deserializeGetValue(proto.getValue, start, end)
            INSTANCE_INITIALIZER_CALL
                -> deserializeInstanceInitializerCall(proto.instanceInitializerCall, start, end)
            PROPERTY_REFERENCE
                -> deserializePropertyReference(proto.propertyReference, start, end, type)
            RETURN
                -> deserializeReturn(proto.`return`, start, end, type)
            SET_FIELD
                -> deserializeSetField(proto.setField, start, end)
            SET_VARIABLE
                -> deserializeSetVariable(proto.setVariable, start, end)
            STRING_CONCAT
                -> deserializeStringConcat(proto.stringConcat, start, end, type)
            THROW
                -> deserializeThrow(proto.`throw`, start, end, type)
            TRY
                -> deserializeTry(proto.`try`, start, end, type)
            TYPE_OP
                -> deserializeTypeOp(proto.typeOp, start, end, type)
            VARARG
                -> deserializeVararg(proto.vararg, start, end, type)
            WHEN
                -> deserializeWhen(proto.`when`, start, end, type)
            WHILE
                -> deserializeWhile(proto.`while`, start, end, type)
            OPERATION_NOT_SET
                -> error("Expression deserialization not implemented: ${proto.operationCase}")
        }

    private fun deserializeExpression(proto: KonanIr.IrExpression): IrExpression {
        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val type = deserializeIrType(proto.type)
        val operation = proto.operation
        val expression = deserializeOperation(operation, start, end, type)

        logger.log{"### Deserialized expression: ${ir2string(expression)} ir_type=$type"}
        return expression
    }

    private fun deserializeIrTypeParameter(proto: KonanIr.IrTypeParameter, start: Int, end: Int, origin: IrDeclarationOrigin): IrTypeParameter {
        val symbol = deserializeIrSymbol(proto.symbol) as IrTypeParameterSymbol
        val name = Name.identifier(proto.name)
        val variance = deserializeIrTypeVariance(proto.variance)
        val parameter = IrTypeParameterImpl(start, end, origin, symbol, name, proto.index, variance)

        val superTypes = proto.superTypeList.map {deserializeIrType(it)}
        parameter.superTypes.addAll(superTypes)
        return parameter
    }

    private fun deserializeIrTypeParameterContainer(proto: KonanIr.IrTypeParameterContainer, start: Int, end: Int, origin: IrDeclarationOrigin): List<IrTypeParameter> {
        return proto.typeParameterList.map { deserializeIrTypeParameter(it, start, end, origin) } // TODO: we need proper start, end and origin here?
    }

    private fun deserializeClassKind(kind: KonanIr.ClassKind) = when (kind) {
        KonanIr.ClassKind.CLASS -> ClassKind.CLASS
        KonanIr.ClassKind.INTERFACE -> ClassKind.INTERFACE
        KonanIr.ClassKind.ENUM_CLASS -> ClassKind.ENUM_CLASS
        KonanIr.ClassKind.ENUM_ENTRY -> ClassKind.ENUM_ENTRY
        KonanIr.ClassKind.ANNOTATION_CLASS -> ClassKind.ANNOTATION_CLASS
        KonanIr.ClassKind.OBJECT -> ClassKind.OBJECT
    }

    private fun deserializeIrValueParameter(proto: KonanIr.IrValueParameter, start: Int, end: Int, origin: IrDeclarationOrigin): IrValueParameter {

        val varargElementType = if (proto.hasVarargElementType()) deserializeIrType(proto.varargElementType) else null

        val parameter = IrValueParameterImpl(start, end, origin,
            deserializeIrSymbol(proto.symbol) as IrValueParameterSymbol,
            Name.identifier(proto.name),
            proto.index,
            deserializeIrType(proto.type),
            varargElementType,
            proto.isCrossinline,
            proto.isNoinline)

        parameter.defaultValue = if (proto.hasDefaultValue()) {
            val expression = deserializeExpression(proto.defaultValue)
            IrExpressionBodyImpl(expression)
        } else null

        return parameter
    }

    private fun deserializeIrClass(proto: KonanIr.IrClass, start: Int, end: Int, origin: IrDeclarationOrigin): IrClass {

        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol

        val clazz = IrClassImpl(start, end, origin,
                symbol,
                Name.identifier(proto.name),
                deserializeClassKind(proto.kind),
                deserializeVisibility(proto.visibility),
                deserializeModality(proto.modality),
                proto.isCompanion,
                proto.isInner,
                proto.isData,
                proto.isExternal)

        proto.declarationContainer.declarationList.forEach {
            val member = deserializeDeclaration(it, clazz)
            clazz.addMember(member)
            member.parent = clazz
        }

        clazz.thisReceiver = deserializeIrValueParameter(proto.thisReceiver, start, end, origin) // TODO: we need proper start, end and origin here?

        val typeParameters = deserializeIrTypeParameterContainer (proto.typeParameters, start, end, origin) // TODO: we need proper start, end and origin here?
        clazz.typeParameters.addAll(typeParameters)

        val superTypes = proto.superTypeList.map { deserializeIrType(it) }
        clazz.superTypes.addAll(superTypes)

        //val symbolTable = context.ir.symbols.symbolTable
        //clazz.createParameterDeclarations(symbolTable)
        //clazz.addFakeOverrides(symbolTable)
        //clazz.setSuperSymbols(symbolTable)

        println("### deserialized IrClass ${clazz.name}")
        println("### IR: ${ir2stringWhole(clazz)}")

        return clazz
    }

    private fun deserializeIrFunctionBase(base: KonanIr.IrFunctionBase, function: IrFunctionBase, start: Int, end: Int, origin: IrDeclarationOrigin) {

        function.returnType = deserializeIrType(base.returnType)
        function.body = if (base.hasBody()) deserializeStatement(base.body) as IrBody else null

        val valueParameters = base.valueParameterList.map { deserializeIrValueParameter(it, start, end, origin) } // TODO
        function.valueParameters.addAll(valueParameters)
        function.dispatchReceiverParameter = if (base.hasDispatchReceiver()) deserializeIrValueParameter(base.dispatchReceiver, start, end, origin) else null // TODO
        function.extensionReceiverParameter = if (base.hasExtensionReceiver()) deserializeIrValueParameter(base.extensionReceiver, start, end, origin) else null // TODO
        val typeParameters = deserializeIrTypeParameterContainer(base.typeParameters, start, end, origin) // TODO
        function.typeParameters.addAll(typeParameters)
    }

    private fun deserializeIrFunction(proto: KonanIr.IrFunction,
                                      start: Int, end: Int, origin: IrDeclarationOrigin, correspondingProperty: IrProperty? = null): IrSimpleFunction {

        logger.log{"### deserializing IrFunction ${proto.base.name}"}
        val symbol = deserializeIrSymbol(proto.symbol) as IrSimpleFunctionSymbol
        val function = IrFunctionImpl(start, end, origin, symbol,
                Name.identifier(proto.base.name),
                deserializeVisibility(proto.base.visibility),
                deserializeModality(proto.modality),
                proto.base.isInline,
                proto.base.isExternal,
                proto.isTailrec,
                proto.isSuspend)

        deserializeIrFunctionBase(proto.base, function, start, end, origin)
        val overridden = proto.overriddenList.map { deserializeIrSymbol(it) as IrSimpleFunctionSymbol }
        function.overriddenSymbols.addAll(overridden)

        function.correspondingProperty = correspondingProperty

//        function.createParameterDeclarations(symbolTable)
//        function.setOverrides(symbolTable)

        println("### deserialized IrFunction ${function.name}")
        println("### IR: ${ir2stringWhole(function)}")
        println("descriptor = ${function.descriptor}")


        return function
    }

    private fun deserializeIrVariable(proto: KonanIr.IrVariable,
                                      start: Int, end: Int, origin: IrDeclarationOrigin): IrVariable {

        val initializer = if (proto.hasInitializer()) {
            deserializeExpression(proto.initializer)
        } else null

        val symbol = deserializeIrSymbol(proto.symbol) as IrVariableSymbol
        val type = deserializeIrType(proto.type)

        val variable = IrVariableImpl(start, end, origin, symbol, Name.identifier(proto.name), type, proto.isVar, proto.isConst, proto.isLateinit)
        variable.initializer = initializer
        return variable
    }

    private fun deserializeIrEnumEntry(proto: KonanIr.IrEnumEntry,
                                       start: Int, end: Int, origin: IrDeclarationOrigin): IrEnumEntry {
        val symbol = deserializeIrSymbol(proto.symbol) as IrEnumEntrySymbol

        val enumEntry = IrEnumEntryImpl(start, end, origin, symbol, Name.identifier(proto.name))
        if (proto.hasCorrespondingClass()) {
            enumEntry.correspondingClass = deserializeDeclaration(proto.correspondingClass, null) as IrClass
        }
        enumEntry.initializerExpression = deserializeExpression(proto.initializer)

        return enumEntry
    }

    private fun deserializeIrAnonymousInit(proto: KonanIr.IrAnonymousInit, start: Int, end: Int, origin: IrDeclarationOrigin): IrAnonymousInitializer {
        val symbol = deserializeIrSymbol(proto.symbol) as IrAnonymousInitializerSymbol
        val initializer = IrAnonymousInitializerImpl(start, end, origin, symbol)
            initializer.body = deserializeBlockBody(proto.body.blockBody, start, end)
        return initializer
    }

    private fun deserializeVisibility(value: String): Visibility {
        return Visibilities.DEFAULT_VISIBILITY // TODO: fixme
    }

    private fun deserializeIrConstructor(proto: KonanIr.IrConstructor, start: Int, end: Int, origin: IrDeclarationOrigin): IrConstructor {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        val constructor = IrConstructorImpl(start, end, origin,
            symbol,
            Name.identifier(proto.base.name),
            deserializeVisibility(proto.base.visibility),
            proto.base.isInline,
            proto.base.isExternal,
            proto.isPrimary
        )

        deserializeIrFunctionBase(proto.base, constructor, start, end, origin)
        return constructor
    }

    private fun deserializeIrField(proto: KonanIr.IrField, start: Int, end: Int, origin: IrDeclarationOrigin): IrField {
        val symbol = deserializeIrSymbol(proto.symbol) as IrFieldSymbol
        val field = IrFieldImpl(start, end, origin,
            symbol,
            Name.identifier(proto.name),
            deserializeIrType(proto.type),
            deserializeVisibility(proto.visibility),
            proto.isFinal,
            proto.isExternal)
        val initializer = if (proto.hasInitializer()) deserializeExpression(proto.initializer) else null
        field.initializer = initializer?.let { IrExpressionBodyImpl(it) }

        return field
    }

    private fun deserializeModality(modality: KonanIr.ModalityKind) = when(modality) {
        KonanIr.ModalityKind.OPEN_MODALITY -> Modality.OPEN
        KonanIr.ModalityKind.SEALED_MODALITY -> Modality.SEALED
        KonanIr.ModalityKind.FINAL_MODALITY -> Modality.FINAL
        KonanIr.ModalityKind.ABSTRACT_MODALITY -> Modality.ABSTRACT
    }

    private fun deserializeIrProperty(proto: KonanIr.IrProperty, start: Int, end: Int, origin: IrDeclarationOrigin): IrProperty {

        val backingField = if (proto.hasBackingField()) deserializeIrField(proto.backingField, start, end, origin) else null

        val descriptor = dummyPropertyDescriptor //declarationTable.valueByIndex(proto.declaration.index)!!.descriptor as PropertyDescriptor

        val property = IrPropertyImpl(start, end, origin,
                descriptor,
                Name.identifier(proto.name),
                deserializeVisibility(proto.visibility),
                deserializeModality(proto.modality),
                proto.isVar,
                proto.isConst,
                proto.isLateinit,
                proto.isDelegated,
                proto.isExternal)

        property.backingField = backingField
        property.getter = if (proto.hasGetter()) deserializeIrFunction(proto.getter, start, end, origin, property) else null
        property.setter = if (proto.hasSetter()) deserializeIrFunction(proto.setter, start, end, origin, property) else null

        property.getter ?. let {deserializedDeclarations.put(it.descriptor, it)}
        property.setter ?. let {deserializedDeclarations.put(it.descriptor, it)}

        return property
    }

    private fun deserializeIrTypeAlias(proto: KonanIr.IrTypeAlias, start: Int, end: Int, origin: IrDeclarationOrigin): IrDeclaration { //IrTypeAlias {
        return IrErrorDeclarationImpl(start, end, errorClassDescriptor)
    }

    override fun deserializeDeclaration(descriptor: DeclarationDescriptor): IrDeclaration {

        println("### deserializeDescriptor descriptor = $descriptor ${descriptor.name}")

        val declaration = deserializedDeclarations[descriptor] ?:
            error("Unknown declaration descriptor: $descriptor")

        return declaration
    }

    private fun deserializeDeclaration(proto: KonanIr.IrDeclaration, parent: IrDeclarationParent?): IrDeclaration {

        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val origin = originIndex[proto.origin.name]!!

        val declarator = proto.declarator

        val declaration: IrDeclaration = when (declarator.declaratorCase){
            IR_TYPE_ALIAS
                -> deserializeIrTypeAlias(declarator.irTypeAlias, start, end, origin)
            IR_ANONYMOUS_INIT
                -> deserializeIrAnonymousInit(declarator.irAnonymousInit, start, end, origin)
            IR_CONSTRUCTOR
                -> deserializeIrConstructor(declarator.irConstructor, start, end, origin)
            IR_FIELD
                -> deserializeIrField(declarator.irField, start, end, origin)
            IR_CLASS
                -> deserializeIrClass(declarator.irClass, start, end, origin)
            IR_FUNCTION
                -> deserializeIrFunction(declarator.irFunction, start, end, origin)
            IR_PROPERTY
                -> deserializeIrProperty(declarator.irProperty, start, end, origin)
            IR_VARIABLE
                -> deserializeIrVariable(declarator.irVariable, start, end, origin)
            IR_ENUM_ENTRY
                -> deserializeIrEnumEntry(declarator.irEnumEntry, start, end, origin)
            DECLARATOR_NOT_SET
                -> error("Declaration deserialization not implemented: ${declarator.declaratorCase}")
        }

        val annotations = deserializeAnnotations(proto.annotations)
        declaration.annotations.addAll(annotations)

        val sourceFileName = proto.fileName

        deserializedDeclarations.put(declaration.descriptor, declaration)
        logger.log{"### Deserialized declaration: ${ir2string(declaration)}"}
        println{"### Deserialized declaration: $declaration ${declaration.descriptor}"}

        return declaration
    }

    val ByteArray.codedInputStream: org.jetbrains.kotlin.protobuf.CodedInputStream
        get() {
            val codedInputStream = org.jetbrains.kotlin.protobuf.CodedInputStream.newInstance(this)
            codedInputStream.setRecursionLimit(65535) // The default 64 is blatantly not enough for IR.
            return codedInputStream
        }

    fun deserializeIrFile(fileProto: KonanIr.IrFile, reader: (Long)->ByteArray): IrFile {
        val fileEntry = NaiveSourceBasedFileEntryImpl(fileProto.fileEntry.name)

        val dummyPackageFragmentDescriptor = EmptyPackageFragmentDescriptor(currentModule!!, FqName("THE_DESCRIPTOR_SHOULD_NOT_BE_NEEDED"))


        val symbol = IrFileSymbolImpl(dummyPackageFragmentDescriptor)
        val file = IrFileImpl(fileEntry, symbol , FqName(fileProto.fqName))
        fileProto.declarationIdList.forEach {
            val stream = reader(it.index).codedInputStream
            val proto = KonanIr.IrDeclaration.parseFrom(stream, KonanSerializerProtocol.extensionRegistry)
            val declaration = deserializeDeclaration(proto, file)
            file.declarations.add(declaration)
            //declaration.parent = file
        }
        return file
    }

    fun deserializeIrModule(proto: KonanIr.IrModule, reader: (Long)->ByteArray): IrModuleFragment {

        val files = proto.fileList.map {
            deserializeIrFile(it, reader)

        }
        return IrModuleFragmentImpl(currentModule!!, builtIns, files)
    }

    fun deserializedIrModule(moduleDescriptor: ModuleDescriptor, byteArray: ByteArray, reader: (Long)->ByteArray): IrModuleFragment {
        println("### deserializedIrModule: $moduleDescriptor")
        currentModule = moduleDescriptor
        val proto = KonanIr.IrModule.parseFrom(byteArray.codedInputStream, KonanSerializerProtocol.extensionRegistry)
        return deserializeIrModule(proto, reader)
    }
}
