package ast;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import parser.BabyCobolParserBaseVisitor;
import parser.BabyCobolParser;

import java.util.*;

public class BuildASTVisitor extends BabyCobolParserBaseVisitor<ASTNode> {

    private SymbolTable symbolTable = new SymbolTable();

    // stack to track record hierarchy: each entry is the currently open record
    // at a given level. when a new entry has a higher level, it becomes a child
    // of the top of the stack. when the level equals or is lower, we pop.
    private Stack<Symbol> recordStack = new Stack<>();

    // paragraph names for PERFORM validation (collected before visiting sentences)
    private Set<String> paragraphNames;

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    @Override
    public ASTNode visitProgram(BabyCobolParser.ProgramContext ctx) {
        ASTNode node = new ASTNode("Program");
        if (ctx.identification() != null) {
            node.addChild(visit(ctx.identification()));
        }
        if (ctx.data() != null) {
            node.addChild(visit(ctx.data()));
        }
        if (ctx.procedure() != null) {
            node.addChild(visit(ctx.procedure()));
        }
        return node;
    }

    @Override
    public ASTNode visitIdentification(BabyCobolParser.IdentificationContext ctx) {
        ASTNode node = new ASTNode("Identification");
        for (TerminalNode id : ctx.ID()) {
            node.addChild(new ASTNode("ID", id.getText()));
        }
        return node;
    }

    @Override
    public ASTNode visitData(BabyCobolParser.DataContext ctx) {
        ASTNode node = new ASTNode("Data");

        Integer firstLevel = null;
        recordStack.clear();

        for (BabyCobolParser.DataLineContext line : ctx.dataLine()) {
            // skip COPY statements (already expanded by preprocessor)
            if (line.copyStmt() != null) {
                continue;
            }

            BabyCobolParser.DataEntryContext entry = line.dataEntry();
            int level = Integer.parseInt(entry.INT().getText());
            if (firstLevel == null) {
                firstLevel = level;
            } else if (level < firstLevel) {
                throw new IllegalArgumentException(
                    "Level number " + String.format("%02d", level) +
                    " cannot be below the first entry's level " + String.format("%02d", firstLevel));
            }
            node.addChild(visit(entry));
        }
        return node;
    }

    @Override
    public ASTNode visitDataEntry(BabyCobolParser.DataEntryContext ctx) {
        ASTNode node = new ASTNode("DataEntry");

        String levelText = ctx.INT().getText();
        if (levelText.length() != 2) {
            throw new IllegalArgumentException(
                "Level number '" + levelText + "' must be exactly two digits (e.g., 01, 05, 10)");
        }

        int level = Integer.parseInt(levelText);
        String id = ctx.ID().getText();

        node.addChild(new ASTNode("Level", String.valueOf(level)));
        node.addChild(new ASTNode("ID", id));

        String picture = "";
        String like = "";
        int occurs = 0;

        for (BabyCobolParser.DataClauseContext clause : ctx.dataClause()) {
            // check for standalone occursClause
            if (clause.pictureClause() == null && clause.likeClause() == null && clause.occursClause() != null) {
                occurs = Integer.parseInt(clause.occursClause().INT().getText());
            } else if (clause.pictureClause() != null) {
                picture = clause.pictureClause().pictureValue().getText();
                // validate PICTURE string
                validatePicture(picture, id);
                if (clause.occursClause() != null) {
                    occurs = Integer.parseInt(clause.occursClause().INT().getText());
                }
            } else if (clause.likeClause() != null) {
                like = qualifiedNameText(clause.likeClause().qualifiedName());
                if (clause.occursClause() != null) {
                    occurs = Integer.parseInt(clause.occursClause().INT().getText());
                }
            }

            node.addChild(visit(clause));
        }

        // Determine parent from recordStack based on level hierarchy
        Symbol parentSymbol = null;

        // Pop entries from the stack whose level is >= the current level
        // (sibling or above - we need the nearest level strictly less than current)
        while (!recordStack.isEmpty() && recordStack.peek().getLevel() >= level) {
            recordStack.pop();
        }
        if (!recordStack.isEmpty()) {
            parentSymbol = recordStack.peek();
        }

        // LIKE resolution: resolve sufficiently qualified name to a unique symbol
        Symbol likeResolved = null;
        if (!like.isEmpty()) {
            likeResolved = resolveQualifiedName(like, "LIKE clause in entry '" + id + "'");
            if (likeResolved == null) {
                throw new IllegalArgumentException(
                    "Unknown symbol '" + like + "' in LIKE clause in entry '" + id + "'" +
                    " (LIKE references unknown symbol '" + like + "')");
            }
            picture = likeResolved.getPicture();
            // if the referenced symbol has no picture (it's a record), copy its children
            if (picture == null || picture.isEmpty()) {
                // LIKE a record: copy its entire substructure
                // mark this as a group by leaving picture empty
                picture = "";
                // record children will be inherited at runtime
            }
            // do not copy referenced.getOccurs() so LIKE resolves the base type only
        }

        // Create the symbol with parent reference
        Symbol symbol = new Symbol(id, level, picture, like, occurs,
            parentSymbol != null ? parentSymbol.getName() : null,
            parentSymbol);
        symbolTable.addSymbol(symbol);

        // If this is a record (no PICTURE, no LIKE that resolves to a field), push onto stack
        boolean isRecord = false;
        if (picture.isEmpty() && like.isEmpty()) {
            isRecord = true;
        } else if (likeResolved != null && (likeResolved.getPicture() == null || likeResolved.getPicture().isEmpty())) {
            // LIKE copied a record so this is also a record
            isRecord = true;
        }

        if (isRecord) {
            recordStack.push(symbol);
        }

        // if LIKE references a record, recursively copy its descendant structure
        if (likeResolved != null && (likeResolved.getPicture() == null || likeResolved.getPicture().isEmpty())) {
            copyRecordStructure(likeResolved, symbol, level);
        }

        return node;
    }

    /**
     * Validate a PICTURE string according to BabyCobol rules:
     * - Only characters 9, A, X, Z, S, V allowed (plus digits for repetition counts and parentheses)
     * - S may appear at most once
     * - V may appear at most once
     */
    private void validatePicture(String picture, String fieldName) {
        if (picture == null || picture.isEmpty()) {
            throw new IllegalArgumentException(
                "PICTURE clause for '" + fieldName + "' is empty");
        }

        String upper = picture.toUpperCase();
        int sCount = 0;
        int vCount = 0;

        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            switch (c) {
                case 'S':
                    sCount++;
                    if (sCount > 1) {
                        throw new IllegalArgumentException(
                            "PICTURE for '" + fieldName + "' has multiple S symbols; only one allowed");
                    }
                    break;
                case 'V':
                    vCount++;
                    if (vCount > 1) {
                        throw new IllegalArgumentException(
                            "PICTURE for '" + fieldName + "' has multiple V symbols; only one allowed");
                    }
                    break;
                case '9':
                case 'A':
                case 'X':
                case 'Z':
                case '(':
                case ')':
                    // valid picture characters
                    break;
                default:
                    if (Character.isDigit(c)) {
                        // digits are valid as repetition counts
                        break;
                    }
                    throw new IllegalArgumentException(
                        "Invalid character '" + c + "' in PICTURE for '" + fieldName + "': " + picture);
            }
        }
    }

    @Override
    public ASTNode visitDataClause(BabyCobolParser.DataClauseContext ctx) {
        ASTNode node = new ASTNode("DataClause");
        if (ctx.pictureClause() != null) {
            node.addChild(visit(ctx.pictureClause()));
        }
        if (ctx.likeClause() != null) {
            node.addChild(visit(ctx.likeClause()));
        }
        if (ctx.occursClause() != null) {
            node.addChild(visit(ctx.occursClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitPictureClause(BabyCobolParser.PictureClauseContext ctx) {
        ASTNode node = new ASTNode("PICTURE_CLAUSE");

        String pictureValue = ctx.pictureValue().getText();

        node.addChild(new ASTNode("PICTURE_VALUE", pictureValue));

        return node;
    }

    @Override
    public ASTNode visitLikeClause(BabyCobolParser.LikeClauseContext ctx) {
        ASTNode node = new ASTNode("LikeClause");
        node.addChild(new ASTNode("ID", qualifiedNameText(ctx.qualifiedName())));
        return node;
    }

    @Override
    public ASTNode visitOccursClause(BabyCobolParser.OccursClauseContext ctx) {
        ASTNode node = new ASTNode("OccursClause", ctx.INT().getText());
        return node;
    }

    @Override
    public ASTNode visitProcedure(BabyCobolParser.ProcedureContext ctx) {
        // pre scan, so collect all paragraph names for PERFORM validation
        this.paragraphNames = new HashSet<>();
        for (BabyCobolParser.ParagraphContext paragraph : ctx.paragraph()) {
            this.paragraphNames.add(paragraph.ID().getText().toLowerCase());
        }

        ASTNode node = new ASTNode("Procedure");
        for (BabyCobolParser.SentenceContext sentence : ctx.sentence()) {
            node.addChild(visit(sentence));
        }
        // traverse and add paragraphs to the AST
        for (BabyCobolParser.ParagraphContext paragraph : ctx.paragraph()) {
            node.addChild(visit(paragraph));
        }

        return node;
    }

    @Override
    public ASTNode visitParagraph(BabyCobolParser.ParagraphContext ctx) {
        // store the paragraph name as node's text
        ASTNode node = new ASTNode("Paragraph", ctx.ID().getText());
        for (BabyCobolParser.SentenceContext sentence : ctx.sentence()) {
            node.addChild(visit(sentence));
        }
        return node;
    }

    @Override
    public ASTNode visitSentence(BabyCobolParser.SentenceContext ctx) {
        ASTNode node = new ASTNode("Sentence");
        for (BabyCobolParser.StatementContext stmt : ctx.statement()) {
            node.addChild(visit(stmt));
        }
        return node;
    }

    @Override
    public ASTNode visitStatement(BabyCobolParser.StatementContext ctx) {
        if (ctx.getChild(0) instanceof ParseTree) {
            return visit(ctx.getChild(0));
        }
        return null; // should not happen (is an error)
    }

    @Override
    public ASTNode visitDisplayStmt(BabyCobolParser.DisplayStmtContext ctx) {
        ASTNode node = new ASTNode("DisplayStmt");
        for (BabyCobolParser.AtomicContext atomic : ctx.atomic()) {
            node.addChild(visit(atomic));
        }
        if (ctx.ADVANCING() != null) {
            node.addChild(new ASTNode("NO ADVANCING"));
        }
        return node;
    }
    
    @Override
    public ASTNode visitAcceptStmt(BabyCobolParser.AcceptStmtContext ctx) {
        ASTNode node = new ASTNode("AcceptStmt");
        for (BabyCobolParser.QualifiedNameContext qn : ctx.qualifiedName()) {
            String qnText = qualifiedNameText(qn);
            String resolvedName = resolveAndGetSimpleName(qnText, "ACCEPT statement");
            node.addChild(new ASTNode("ID", resolvedName));
        }
        return node;
    }

    @Override
    public ASTNode visitAddStmt(BabyCobolParser.AddStmtContext ctx) {
        ASTNode node = new ASTNode("AddStmt");
        for (BabyCobolParser.AtomicContext atomic : ctx.atomic()) {
            node.addChild(visit(atomic));
        }
        
        BabyCobolParser.AtomicContext toAtomic = ctx.atomic(ctx.atomic().size() - 1);
        if ((toAtomic.INT() != null || toAtomic.STRING() != null) && ctx.givingClause() == null) {
            throw new IllegalArgumentException("If the second argument of ADD is a literal, the GIVING clause is mandatory.");
        }
        
        if (ctx.givingClause() != null) {
            node.addChild(visit(ctx.givingClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitDivideStmt(BabyCobolParser.DivideStmtContext ctx) {
        ASTNode node = new ASTNode("DivideStmt");
        for (BabyCobolParser.AtomicContext atomic : ctx.atomic()) {
            node.addChild(visit(atomic));
        }
        
        for (int i = 1; i < ctx.atomic().size(); i++) {
            BabyCobolParser.AtomicContext secondArg = ctx.atomic(i);
            if ((secondArg.INT() != null || secondArg.STRING() != null) && ctx.givingRemainderClause() == null) {
                throw new IllegalArgumentException("If the second argument is a literal, the third argument is mandatory.");
            }
        }

        if (ctx.givingRemainderClause() != null) {
            if (ctx.atomic().size() > 2) {
                throw new IllegalArgumentException("If the third argument is present, there can be only one second argument.");
            }
            if (ctx.givingRemainderClause().remainderClause() != null && ctx.givingRemainderClause().qualifiedName().size() > 1) {
                throw new IllegalArgumentException("If the fourth argument is present, there can be only one third argument.");
            }
            node.addChild(visit(ctx.givingRemainderClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitMulStmt(BabyCobolParser.MulStmtContext ctx) {
        ASTNode node = new ASTNode("MulStmt");
        for (BabyCobolParser.AtomicContext atomic : ctx.atomic()) {
            node.addChild(visit(atomic));
        }

        BabyCobolParser.AtomicContext toAtomic = ctx.atomic(ctx.atomic().size() - 1);
        if ((toAtomic.INT() != null || toAtomic.STRING() != null) && ctx.givingClause() == null) {
            throw new IllegalArgumentException("If the second argument of MULTIPLY is a literal, the GIVING clause is mandatory.");
        }

        if (ctx.givingClause() != null) {
            node.addChild(visit(ctx.givingClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitIfStmt(BabyCobolParser.IfStmtContext ctx) {
        ASTNode node = new ASTNode("IfStmt");
        node.addChild(visit(ctx.anyExpression()));
        
        ASTNode thenNode = new ASTNode("Then");
        for (BabyCobolParser.StatementContext stmt : ctx.statement()) {
            thenNode.addChild(visit(stmt));
        }
        node.addChild(thenNode);
        
        if (ctx.elseStmt() != null) {
            node.addChild(visit(ctx.elseStmt()));
        }
        return node;
    }

    @Override
    public ASTNode visitElseStmt(BabyCobolParser.ElseStmtContext ctx) {
        ASTNode node = new ASTNode("ElseStmt");
        for (BabyCobolParser.StatementContext stmt : ctx.statement()) {
            node.addChild(visit(stmt));
        }
        return node;
    }

    @Override
    public ASTNode visitMoveStmt(BabyCobolParser.MoveStmtContext ctx) {
        ASTNode node = new ASTNode("MoveStmt");
        node.addChild(visit(ctx.atomic()));
        for (BabyCobolParser.QualifiedNameContext qn : ctx.qualifiedName()) {
            String qnText = qualifiedNameText(qn);
            String resolvedName = resolveAndGetSimpleName(qnText, "MOVE statement");
            node.addChild(new ASTNode("ToID", resolvedName));
        }
        return node;
    }

    @Override
    public ASTNode visitPerformStmt(BabyCobolParser.PerformStmtContext ctx) {
        String targetName = ctx.ID().getText().toLowerCase();

        if (paragraphNames != null && !paragraphNames.contains(targetName)) {
            throw new IllegalArgumentException(
                "PERFORM target paragraph '" + ctx.ID().getText() + "' does not exist.");
        }

        ASTNode node = new ASTNode("PerformStmt");
        node.addChild(new ASTNode("ID", ctx.ID().getText()));
        if (ctx.throughClause() != null) {
            node.addChild(visit(ctx.throughClause()));
        }
        if (ctx.timesClause() != null) {
            node.addChild(visit(ctx.timesClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitThroughClause(BabyCobolParser.ThroughClauseContext ctx) {
        String throughName = ctx.ID().getText().toLowerCase();

        if (paragraphNames != null && !paragraphNames.contains(throughName)) {
            throw new IllegalArgumentException(
                "PERFORM THROUGH paragraph '" + ctx.ID().getText() + "' does not exist.");
        }

        return new ASTNode("ThroughClause", ctx.ID().getText());
    }

    @Override
    public ASTNode visitTimesClause(BabyCobolParser.TimesClauseContext ctx) {
        ASTNode node = new ASTNode("TimesClause");
        node.addChild(visit(ctx.atomic()));
        return node;
    }

    @Override
    public ASTNode visitEvaluateStmt(BabyCobolParser.EvaluateStmtContext ctx) {
        ASTNode node = new ASTNode("EvaluateStmt");
        node.addChild(visit(ctx.anyExpression()));
        for (BabyCobolParser.AlsoClauseContext also : ctx.alsoClause()) {
            node.addChild(visit(also));
        }
        for (BabyCobolParser.WhenClauseStatementContext when : ctx.whenClauseStatement()) {
            node.addChild(visit(when));
        }
        return node;
    }

    @Override
    public ASTNode visitAlsoClause(BabyCobolParser.AlsoClauseContext ctx) {
        ASTNode node = new ASTNode("AlsoClause");
        node.addChild(visit(ctx.anyExpression()));
        return node;
    }

    @Override
    public ASTNode visitWhenClauseStatement(BabyCobolParser.WhenClauseStatementContext ctx) {
        ASTNode node = new ASTNode("WhenClauseStatement");
        node.addChild(visit(ctx.whenClause()));
        for (BabyCobolParser.StatementContext stmt : ctx.statement()) {
            node.addChild(visit(stmt));
        }
        return node;
    }

    @Override
    public ASTNode visitWhenClause(BabyCobolParser.WhenClauseContext ctx) {
        ASTNode node = new ASTNode("WhenClause");
        if (ctx.OTHER() != null) {
            node.addChild(new ASTNode("OTHER"));
        } else if (ctx.whenSubject() != null) {
            node.addChild(visit(ctx.whenSubject()));
        }
        return node;
    }

    @Override
    public ASTNode visitWhenSubject(BabyCobolParser.WhenSubjectContext ctx) {
        ASTNode node = new ASTNode("WhenSubject");
        for (BabyCobolParser.SubWhenSubjectContext sub : ctx.subWhenSubject()) {
            node.addChild(visit(sub));
        }
        return node;
    }

    @Override
    public ASTNode visitSubWhenSubject(BabyCobolParser.SubWhenSubjectContext ctx) {
        ASTNode node = new ASTNode("SubWhenSubject");
        for (BabyCobolParser.WhenValueContext wv : ctx.whenValue()) {
            node.addChild(visit(wv));
        }
        return node;
    }

    @Override
    public ASTNode visitWhenValue(BabyCobolParser.WhenValueContext ctx) {
        ASTNode node = new ASTNode("WhenValue");
        // first whenValueExpression is always present
        node.addChild(visit(ctx.whenValueExpression(0)));
        
        // second whenValueExpression is for the THROUGH range end
        if (ctx.whenValueExpression().size() > 1) {
            node.addChild(visit(ctx.whenValueExpression(1)));
        }
        return node;
    }

    @Override
    public ASTNode visitWhenValueExpression(BabyCobolParser.WhenValueExpressionContext ctx) {
        ASTNode node = new ASTNode("WhenValueExpression");
        node.addChild(visit(ctx.contractedRelationalExpression(0)));
        for (int i = 1; i < ctx.contractedRelationalExpression().size(); i++) {
            ASTNode andNode = new ASTNode("LogicalOp", "AND");
            node.addChild(andNode);
            node.addChild(visit(ctx.contractedRelationalExpression(i)));
        }
        return node;
    }

    @Override
    public ASTNode visitAnyExpression(BabyCobolParser.AnyExpressionContext ctx) {
        ASTNode node = new ASTNode("AnyExpression");
        node.addChild(visit(ctx.logicalExpression()));
        return node;
    }

    @Override
    public ASTNode visitLogicalExpression(BabyCobolParser.LogicalExpressionContext ctx) {
        ASTNode node = new ASTNode("LogicalExpression");
        node.addChild(visit(ctx.relationalExpression()));
        if (ctx.contractedRelationalExpression() != null && !ctx.contractedRelationalExpression().isEmpty()) {
            for (int i = 0; i < ctx.contractedRelationalExpression().size(); i++) {
                String op = ctx.getChild(2 * i + 1).getText();
                node.addChild(new ASTNode("LogicalOp", op));
                node.addChild(visit(ctx.contractedRelationalExpression(i)));
            }
        }
        return node;
    }
    
    @Override
    public ASTNode visitContractedRelationalExpression(BabyCobolParser.ContractedRelationalExpressionContext ctx) {
        ASTNode node = new ASTNode("ContractedRelationalExpression");
        if (ctx.relationalExpression() != null) {
            node.addChild(visit(ctx.relationalExpression()));
        } else if (ctx.relationalOperator() != null) {
            node.addChild(visit(ctx.relationalOperator()));
            node.addChild(visit(ctx.additiveExpression()));
        } else if (ctx.additiveExpression() != null) {
            node.addChild(visit(ctx.additiveExpression()));
        }
        return node;
    }
    
    @Override
    public ASTNode visitRelationalExpression(BabyCobolParser.RelationalExpressionContext ctx) {
        ASTNode node = new ASTNode("RelationalExpression");
        node.addChild(visit(ctx.additiveExpression(0)));
        for (int i = 1; i < ctx.additiveExpression().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            node.addChild(new ASTNode("RelationalOp", op));
            node.addChild(visit(ctx.additiveExpression(i)));
        }
        return node;
    }

    @Override
    public ASTNode visitAdditiveExpression(BabyCobolParser.AdditiveExpressionContext ctx) {
        ASTNode node = new ASTNode("AdditiveExpression");
        node.addChild(visit(ctx.multiplicativeExpression(0)));
        for (int i = 1; i < ctx.multiplicativeExpression().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            node.addChild(new ASTNode("AdditiveOp", op));
            node.addChild(visit(ctx.multiplicativeExpression(i)));
        }
        return node;
    }

    @Override
    public ASTNode visitMultiplicativeExpression(BabyCobolParser.MultiplicativeExpressionContext ctx) {
        ASTNode node = new ASTNode("MultiplicativeExpression");
        node.addChild(visit(ctx.unaryExpression(0)));
        for (int i = 1; i < ctx.unaryExpression().size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            node.addChild(new ASTNode("MultiplicativeOp", op));
            node.addChild(visit(ctx.unaryExpression(i)));
        }
        return node;
    }

    @Override
    public ASTNode visitUnaryExpression(BabyCobolParser.UnaryExpressionContext ctx) {
        ASTNode node = new ASTNode("UnaryExpression");
        if (ctx.PLUS() != null || ctx.MINUS() != null || ctx.NOT() != null) {
            node.addChild(new ASTNode("UnaryOp", ctx.getChild(0).getText()));
        }
        node.addChild(visit(ctx.primaryExpression()));
        return node;
    }

    @Override
    public ASTNode visitPrimaryExpression(BabyCobolParser.PrimaryExpressionContext ctx) {
        ASTNode node = new ASTNode("PrimaryExpression");
        if (ctx.atomic() != null) {
            node.addChild(visit(ctx.atomic()));
        } else if (ctx.anyExpression() != null) {
            node.addChild(visit(ctx.anyExpression()));
        }
        return node;
    }

    @Override
    public ASTNode visitStopStmt(BabyCobolParser.StopStmtContext ctx) {
        return new ASTNode("StopStmt");
    }

    @Override
    public ASTNode visitNextSentenceStmt(BabyCobolParser.NextSentenceStmtContext ctx) {
        return new ASTNode("NextSentenceStmt");
    }

    @Override
    public ASTNode visitGoToStmt(BabyCobolParser.GoToStmtContext ctx) {
        return new ASTNode("GoToStmt", ctx.ID().getText());
    }

    @Override
    public ASTNode visitAlterStmt(BabyCobolParser.AlterStmtContext ctx) {
        ASTNode node = new ASTNode("AlterStmt");
        // ALTER <source> TO PROCEED TO <target>
        node.addChild(new ASTNode("ID", ctx.ID(0).getText()));
        node.addChild(new ASTNode("ID", ctx.ID(1).getText()));
        return node;
    }

    @Override
    public ASTNode visitSignalStmt(BabyCobolParser.SignalStmtContext ctx) {
        ASTNode node = new ASTNode("SignalStmt");
        if (ctx.OFF() != null) {
            node.addChild(new ASTNode("Off"));
        } else {
            node.addChild(new ASTNode("ID", ctx.ID().getText()));
        }
        return node;
    }

    @Override
    public ASTNode visitCallStmt(BabyCobolParser.CallStmtContext ctx) {
        return new ASTNode("CallStmt", ctx.ID().getText());
    }

    @Override
    public ASTNode visitSubtractStmt(BabyCobolParser.SubtractStmtContext ctx) {
        ASTNode node = new ASTNode("SubtractStmt");
        for (BabyCobolParser.AtomicContext atomic : ctx.atomic()) {
            node.addChild(visit(atomic)); // this collects both FROM and terms.
        }

        BabyCobolParser.AtomicContext toAtomic = ctx.atomic(ctx.atomic().size() - 1);
        if ((toAtomic.INT() != null || toAtomic.STRING() != null) && ctx.givingClause() == null) {
            throw new IllegalArgumentException("If the second argument of SUBTRACT is a literal, the GIVING clause is mandatory.");
        }

        if (ctx.givingClause() != null) {
            node.addChild(visit(ctx.givingClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitGivingClause(BabyCobolParser.GivingClauseContext ctx) {
        ASTNode node = new ASTNode("GivingClause");
        for (BabyCobolParser.QualifiedNameContext qn : ctx.qualifiedName()) {
            String qnText = qualifiedNameText(qn);
            String resolvedName = resolveAndGetSimpleName(qnText, "GIVING clause");
            node.addChild(new ASTNode("ID", resolvedName));
        }
        return node;
    }

    @Override
    public ASTNode visitGivingRemainderClause(BabyCobolParser.GivingRemainderClauseContext ctx) {
        ASTNode node = new ASTNode("GivingRemainderClause");
        for (BabyCobolParser.QualifiedNameContext qn : ctx.qualifiedName()) {
            String qnText = qualifiedNameText(qn);
            String resolvedName = resolveAndGetSimpleName(qnText, "GIVING clause");
            node.addChild(new ASTNode("ID", resolvedName));
        }
        if (ctx.remainderClause() != null) {
            node.addChild(visit(ctx.remainderClause()));
        }
        return node;
    }

    @Override
    public ASTNode visitRemainderClause(BabyCobolParser.RemainderClauseContext ctx) {
        String qnText = qualifiedNameText(ctx.qualifiedName());
        String resolvedName = resolveAndGetSimpleName(qnText, "REMAINDER clause");
        return new ASTNode("RemainderClause", resolvedName);
    }

    @Override
    public ASTNode visitLoopStmt(BabyCobolParser.LoopStmtContext ctx) {
        ASTNode node = new ASTNode("LoopStmt");
        for (BabyCobolParser.LoopElementContext el : ctx.loopElement()) {
            node.addChild(visit(el));
        }
        return node;
    }

    @Override
    public ASTNode visitLoopElement(BabyCobolParser.LoopElementContext ctx) {
        if (ctx.getChild(0) instanceof ParseTree) {
            return visit(ctx.getChild(0));
        }
        return null;
    }

    @Override
    public ASTNode visitVaryingClause(BabyCobolParser.VaryingClauseContext ctx) {
        ASTNode node = new ASTNode("VaryingClause");
        String qnText = qualifiedNameText(ctx.qualifiedName());
        String resolvedName = resolveAndGetSimpleName(qnText, "VARYING clause");
        node.addChild(new ASTNode("ID", resolvedName));
        
        // track position in atomic() since some clauses can be omitted
        int atomicIndex = 0;
        
        if (ctx.FROM() != null) {
            ASTNode fromNode = new ASTNode("From");
            fromNode.addChild(visit(ctx.atomic(atomicIndex++)));
            node.addChild(fromNode);
        }
        
        if (ctx.TO() != null) {
            ASTNode toNode = new ASTNode("To");
            toNode.addChild(visit(ctx.atomic(atomicIndex++)));
            node.addChild(toNode);
        }
        
        if (ctx.BY() != null) {
            ASTNode byNode = new ASTNode("By");
            byNode.addChild(visit(ctx.atomic(atomicIndex++)));
            node.addChild(byNode);
        }
        
        return node;
    }

    @Override
    public ASTNode visitWhileClause(BabyCobolParser.WhileClauseContext ctx) {
        ASTNode node = new ASTNode("WhileClause");
        node.addChild(visit(ctx.anyExpression()));
        return node;
    }

    @Override
    public ASTNode visitUntilClause(BabyCobolParser.UntilClauseContext ctx) {
        ASTNode node = new ASTNode("UntilClause");
        node.addChild(visit(ctx.anyExpression()));
        return node;
    }

    @Override
    public ASTNode visitAtomic(BabyCobolParser.AtomicContext ctx) {
        if (ctx.qualifiedName() != null) {
            String qnText = qualifiedNameText(ctx.qualifiedName());
            String resolvedName = resolveAndGetSimpleName(qnText, "expression");
            return new ASTNode("AtomicID", resolvedName);
        } else if (ctx.INT() != null) {
            return new ASTNode("AtomicInt", ctx.INT().getText());

        } else if (ctx.DECIMAL() != null) {
            return new ASTNode("AtomicDecimal", ctx.DECIMAL().getText());

        } else if (ctx.STRING() != null) {
            return new ASTNode("AtomicString", ctx.STRING().getText());

        } else if (ctx.SPACES() != null) {
            return new ASTNode("AtomicSpaces", ctx.SPACES().getText());

        } else if (ctx.HIGH_VALUES() != null) {
            return new ASTNode("AtomicHighValues", ctx.HIGH_VALUES().getText());

        } else if (ctx.LOW_VALUES() != null) {
            return new ASTNode("AtomicLowValues", ctx.LOW_VALUES().getText());
        }
        return new ASTNode("Atomic");
    }

    @Override
    public ASTNode visitRelationalOperator(BabyCobolParser.RelationalOperatorContext ctx) {
        return new ASTNode("RelationalOperator", ctx.getText());
    }

    // --------
    // sufficient qualification resolving stuff

    /**
     * returns the textual representation of a qualified name as written
     * in the source, with segments joined by " OF "
     */
    private String qualifiedNameText(BabyCobolParser.QualifiedNameContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (TerminalNode id : ctx.ID()) {
            if (sb.length() > 0) {
                sb.append(" OF ");
            }
            sb.append(id.getText());
        }
        return sb.toString();
    }

    /**
     * splits a textual qualified name into its segments
     */
    private List<String> splitQualifiedName(String qualifiedName) {
        List<String> parts = new ArrayList<>();
        for (String part : qualifiedName.split("(?i)\\s+OF\\s+")) {
            parts.add(part.trim());
        }
        return parts;
    }

    /**
     * resolves a qualified or unqualified name to a unique symbol,
     * then returns just the simple (last segment) name.
     * Throws error if ambiguous or insufficient qualification
     */
    private String resolveAndGetSimpleName(String qualifiedName, String contextDescription) {
        Symbol resolved = resolveQualifiedName(qualifiedName, contextDescription);
        if (resolved != null) {
            return resolved.getName();
        }
        // Symbol not in the table (no DATA DIVISION or special identifier like TRUE):
        // return just the simple (first) name part
        List<String> parts = splitQualifiedName(qualifiedName);
        return parts.isEmpty() ? qualifiedName : parts.get(0);
    }

    /**
     * resolves a qualified or unqualified name to a unique symbol
     * the last segment is the target field name, preceding segments are
     * higher level qualifiers that must appear on a path from the target
     * towards the root. ambiguous references make a compilation error
     */
    private Symbol resolveQualifiedName(String qualifiedName, String contextDescription) {
        List<String> parts = splitQualifiedName(qualifiedName);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Empty qualified name in " + contextDescription);
        }

        String targetName = parts.get(0);
        List<String> requiredQualifiers = parts.subList(1, parts.size());

        // collect all symbols with the target name
        List<Symbol> candidates = symbolTable.getSymbolsByName(targetName);

        // if no symbols with this name exist just return the simple name as is.
        // this handles cases where the data division is empty or the symbol is
        // a special identifier (e.g. TRUE) not defined in the symbol table
        if (candidates.isEmpty()) {
            return null;
        }

        // filter by required qualifiers ie must appear on the ancestor chain
        List<Symbol> matches = new ArrayList<>();
        for (Symbol candidate : candidates) {
            if (hasQualifiers(candidate, requiredQualifiers)) {
                matches.add(candidate);
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                "Insufficient qualification for '" + qualifiedName + "' in " + contextDescription);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                "Ambiguous reference '" + qualifiedName + "' in " + contextDescription);
        }

        return matches.get(0);
    }

    /**
     * checks whether the given symbol's ancestor chain (traversed via parent reference)
     * contains every required qualifier. the qualifier list is written from target to root
     * (e.g. for "D OF C OF B", qualifiers are [C, B]).
     * We walk up the parent chain and verify each required qualifier appears in sequence from the target upward
     */
    private boolean hasQualifiers(Symbol symbol, List<String> requiredQualifiers) {
        if (requiredQualifiers.isEmpty()) {
            return true;
        }

        Symbol current = symbol;
        // qualifiers are listed from innermost to outermost: [C, B, A] for "D OF C OF B OF A"
        // We need to match them in order as we walk up the parent chain
        int qualifierIndex = 0;

        while (current.getParent() != null && qualifierIndex < requiredQualifiers.size()) {
            Symbol parent = current.getParent();
            if (parent.getName().equalsIgnoreCase(requiredQualifiers.get(qualifierIndex))) {
                qualifierIndex++;
            }
            current = parent;
        }

        return qualifierIndex >= requiredQualifiers.size();
    }

    /**
     * NOT CONFIDENT IN THE FOLLOWING, NEED MORE TINKERING AND TESTING **
     * recursively copies all descendant symbols from a source record to a target
     * new parent symbol. each descendant gets cloned with the new parent reference,
     * preserving names, levels, picture values, and occurs counts.
     * The level offset is the difference between the new parent's level and the source parent's level.
     */
    private void copyRecordStructure(Symbol sourceRecord, Symbol newParent, int newParentLevel) {
        int levelOffset = newParentLevel - sourceRecord.getLevel();

        // find all symbols whose parent is the source record, ordered by their occurrence
        // We need to process them in order - get all symbols and filter
        for (Symbol candidate : symbolTable.getAllSymbols()) {
            if (candidate.getParent() == sourceRecord) {
                int newLevel = candidate.getLevel() + levelOffset;
                String pic = candidate.getPicture();
                String like = candidate.getLike();
                int occurs = candidate.getOccurs();

                Symbol cloned = new Symbol(candidate.getName(), newLevel, pic, like, occurs,
                    newParent.getName(), newParent);
                symbolTable.addSymbol(cloned);

                // if this clone is a record (no picture) recursively copy its children too
                if ((pic == null || pic.isEmpty()) && (like == null || like.isEmpty())) {
                    copyRecordStructure(candidate, cloned, newLevel);
                } else if (like != null && !like.isEmpty() && (pic == null || pic.isEmpty())) {
                    // LIKE of a record
                    copyRecordStructure(candidate, cloned, newLevel);
                }
            }
        }
    }
}
