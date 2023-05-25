package org.jetbrains.kotlin.fir.dataframe

import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.dataframe.extensions.ScopesGenerator
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.builder.buildReceiverParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.toFirResolvedTypeRef
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

internal fun FirDeclarationGenerationExtension.generateExtensionProperty(
        callableId: CallableId,
        receiverType: ConeClassLikeTypeImpl,
        propertyName: Name,
        returnTypeRef: FirResolvedTypeRef
): FirProperty {
    val firPropertySymbol = FirPropertySymbol(callableId)
    return buildProperty {
        moduleData = session.moduleData
        resolvePhase = FirResolvePhase.BODY_RESOLVE
        origin = FirDeclarationOrigin.Plugin(ScopesGenerator.DataFramePlugin)
        status = FirResolvedDeclarationStatusImpl(
                Visibilities.Public,
                Modality.FINAL,
                EffectiveVisibility.Public
        )
        this.returnTypeRef = returnTypeRef
        receiverParameter = buildReceiverParameter {
            typeRef = receiverType.toFirResolvedTypeRef()
        }
        val classId = callableId.classId
        if (classId != null) {
            dispatchReceiverType = ConeClassLikeTypeImpl(
                    ConeClassLikeLookupTagImpl(classId),
                    emptyArray(),
                    false
            )
        }
        val firPropertyAccessorSymbol = FirPropertyAccessorSymbol()
        getter = buildPropertyAccessor {
            moduleData = session.moduleData
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            origin = FirDeclarationOrigin.Plugin(ScopesGenerator.DataFramePlugin)
            this.returnTypeRef = returnTypeRef
            dispatchReceiverType = receiverType
            symbol = firPropertyAccessorSymbol
            propertySymbol = firPropertySymbol
            isGetter = true
            status = FirResolvedDeclarationStatusImpl(
                    Visibilities.Public,
                    Modality.FINAL,
                    EffectiveVisibility.Public
            )
        }.also { firPropertyAccessorSymbol.bind(it) }
        name = propertyName
        symbol = firPropertySymbol
        isVar = false
        isLocal = false
    }.also { firPropertySymbol.bind(it) }
}
