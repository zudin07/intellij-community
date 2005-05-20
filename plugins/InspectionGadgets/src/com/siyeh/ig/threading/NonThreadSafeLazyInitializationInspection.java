package com.siyeh.ig.threading;

import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.ExpressionInspection;
import com.siyeh.ig.GroupNames;
import org.jetbrains.annotations.NotNull;

public class NonThreadSafeLazyInitializationInspection
                                                       extends ExpressionInspection{
     public String getDisplayName(){
        return "Unsafe lazy initialization of static field";
    }

    public String getGroupDisplayName(){
        return GroupNames.INITIALIZATION_GROUP_NAME;
    }

    public String buildErrorString(PsiElement location){
        return "Lazy initialization of static field '#ref' is not thread-safe #loc";
    }

    public BaseInspectionVisitor buildVisitor(){
        return new UnsafeSafeLazyInitializationVisitor();
    }

    private static class UnsafeSafeLazyInitializationVisitor
                                                         extends BaseInspectionVisitor{
        public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression){
            super.visitAssignmentExpression(expression);
            final PsiExpression lhs = expression.getLExpression();
            if(!(lhs instanceof PsiReferenceExpression)){
                return;
            }
            final PsiElement referent = ((PsiReference) lhs).resolve();
            if(!(referent instanceof PsiField)){
                return;
            }
            final PsiField field = (PsiField) referent;
            if(!field.hasModifierProperty(PsiModifier.STATIC)){
                return;
            }
            if(isInStaticInitializer(expression)){
                return;
            }
            if(isInSynchronizedContext(expression)){
                return;
            }
            if(!isLazy(expression, (PsiReferenceExpression) lhs)){
                return;
            }
            registerError(lhs);
        }

        private boolean isLazy(PsiAssignmentExpression expression,
                            PsiReferenceExpression lhs){
            final PsiIfStatement ifStatement =
                    PsiTreeUtil.getParentOfType(expression,
                                                PsiIfStatement.class);
            if(ifStatement == null)
            {
                return false;
            }
            final PsiExpression condition = ifStatement.getCondition();
            if(condition == null)
            {
                return false;
            }
            return isNullComparison(condition, lhs);
        }

        private boolean isNullComparison(PsiExpression condition,
                                         PsiReferenceExpression reference){
            if(!(condition instanceof PsiBinaryExpression)){
                return false;
            }
            final PsiBinaryExpression comparison = (PsiBinaryExpression) condition;
            final PsiJavaToken sign = comparison.getOperationSign();
            final IElementType tokenType = sign.getTokenType();
            if(!tokenType.equals(JavaTokenType.EQEQ))
            {
                return false;
            }
            final PsiExpression lhs = comparison.getLOperand();
            final PsiExpression rhs = comparison.getROperand();
            if(lhs == null || rhs == null)
            {
                return false;
            }
            final String lhsText = lhs.getText();
            final String rhsText = rhs.getText();
            if(!"null".equals(lhsText)&& !"null".equals(rhsText))
            {
                return false;
            }
            final String referenceText = reference.getText();
            return referenceText.equals(lhsText) ||
                    referenceText.equals(rhsText);
        }

        private static boolean isInSynchronizedContext(PsiElement element){
            final PsiSynchronizedStatement syncBlock =
                    PsiTreeUtil.getParentOfType(element,
                                                PsiSynchronizedStatement.class);
            if(syncBlock != null){
                return true;
            }
            final PsiMethod method =
                    PsiTreeUtil.getParentOfType(element,
                                                PsiMethod.class);
            return method != null &&
                    method.hasModifierProperty(PsiModifier.SYNCHRONIZED)
                    && method.hasModifierProperty(PsiModifier.STATIC);
        }

        private static boolean isInStaticInitializer(PsiElement element){
            final PsiClassInitializer initializer =
                    PsiTreeUtil.getParentOfType(element,
                                                PsiClassInitializer.class);
            return initializer != null &&
                    initializer.hasModifierProperty(PsiModifier.STATIC);
        }
    }
}
