package datawave.query.jexl.visitors.validate;

import datawave.query.exceptions.InvalidQueryTreeException;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.visitors.JexlStringBuildingVisitor;
import datawave.query.jexl.visitors.RebuildingVisitor;
import datawave.query.jexl.visitors.TreeFlatteningRebuildingVisitor;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.log4j.Logger;

/**
 * Validates an AST with respect to some basic assumptions. Intended to be called after a given visitor is done visiting a query tree.
 *
 * <pre>
 *     1. Proper lineage with respect to parent-child pointers
 *     2. The tree is flattened
 * </pre>
 */
public class ASTValidator {
    
    private static final Logger log = Logger.getLogger(ASTValidator.class);
    
    // this is a static utility class
    private ASTValidator() {}
    
    /**
     * Determines if the provided AST meets all basic assumptions. Intended to be called outside of normal visitor validation
     *
     * @param root
     *            an arbitrary JexlNode
     * @return true if the tree is valid, otherwise throws an exception
     */
    public static boolean isValid(JexlNode root) throws InvalidQueryTreeException {
        return isValid(root, null, true);
    }
    
    /**
     * Determines if the provided AST meets all basic assumptions of validity. Intended to be called after a visitor is done visiting a query tree.
     *
     * @param root
     *            an arbitrary JexlNode
     * @param sourceVisitor
     *            the visitor calling this method
     * @return true if the AST is valid, otherwise
     */
    public static boolean isValid(JexlNode root, String sourceVisitor) throws InvalidQueryTreeException {
        return isValid(root, sourceVisitor, true);
    }
    
    /**
     * Determines if the AST meets all basic assumptions of validity. Throws an exception if the AST if not valid.
     *
     * @param root
     *            an arbitrary JexlNode
     * @param sourceVisitor
     *            the visitor calling this method, may be null if called outside normal scope
     * @param failHard
     *            boolean to throw an exception if the tree fails validation
     * @return true if the AST is valid, otherwise throws an exception
     */
    public static boolean isValid(JexlNode root, String sourceVisitor, boolean failHard) throws InvalidQueryTreeException {
        
        log.info("Validating tree after visiting " + sourceVisitor);
        
        boolean isLineageValid = isLineageValid(root);
        boolean isTreeFlattened = isTreeFlattened(root);
        
        //  @formatter:off
        boolean isValid = isLineageValid && isTreeFlattened;
        //  @formatter:on
        
        if (!isValid) {
            
            if (!isLineageValid)
                log.error("Valid Lineage: " + isLineageValid);
            if (!isTreeFlattened)
                log.error("Valid flatten: " + isTreeFlattened);
            
            log.error(sourceVisitor + " produced an invalid query tree, see logs for details.");
            if (failHard) {
                if (sourceVisitor != null) {
                    throw new InvalidQueryTreeException(sourceVisitor + " produced an invalid query tree, see logs for details.");
                } else {
                    throw new InvalidQueryTreeException("Invalid query tree detected outside of normal scope, see logs for details");
                }
            }
        }
        return isValid;
    }
    
    /**
     * Validate the lineage of all nodes under this root
     *
     * @param root
     *            an arbitrary JexlNode
     * @return true if all parent-child relationships are valid
     */
    private static boolean isLineageValid(JexlNode root) {
        // Do not fail hard
        return JexlASTHelper.validateLineage(root, false);
    }
    
    /**
     * Determine if this AST is flattened
     *
     * @param root
     *            an arbitrary JexlNode
     * @return true if the tree is flattened
     */
    private static boolean isTreeFlattened(JexlNode root) {
        JexlNode copy = RebuildingVisitor.copy(root);
        String original = JexlStringBuildingVisitor.buildQueryWithoutParse(copy);
        String flattened = JexlStringBuildingVisitor.buildQueryWithoutParse(TreeFlatteningRebuildingVisitor.flatten(copy));
        return original.equals(flattened);
    }
}
