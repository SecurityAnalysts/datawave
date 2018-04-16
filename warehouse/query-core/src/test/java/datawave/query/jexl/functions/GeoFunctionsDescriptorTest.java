package datawave.query.jexl.functions;

import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.functions.arguments.JexlArgumentDescriptor;
import datawave.query.jexl.visitors.JexlStringBuildingVisitor;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.JexlNode;
import org.junit.Assert;
import org.junit.Test;

public class GeoFunctionsDescriptorTest {
    
    @Test
    public void antiMeridianTest1() throws Exception {
        String query = "geo:within_bounding_box(GEO_FIELD, '40_170', '50_-170')";
        JexlNode node = JexlASTHelper.parseJexlQuery(query);
        JexlArgumentDescriptor argDesc = new GeoFunctionsDescriptor().getArgumentDescriptor((ASTFunctionNode) node.jjtGetChild(0).jjtGetChild(0));
        JexlNode queryNode = argDesc.getIndexQuery(null, null, null, null);
        Assert.assertEquals("((GEO_FIELD >= '40.0_170.0' && GEO_FIELD <= '50.0_180') && (GEO_FIELD >= '40.0_-180' && GEO_FIELD <= '50.0_-170.0'))",
                        JexlStringBuildingVisitor.buildQuery(queryNode));
    }
    
    @Test
    public void antiMeridianTest2() throws Exception {
        String query = "geo:within_bounding_box(LON_FIELD, LAT_FIELD, '170', '40', '-170', '50')";
        JexlNode node = JexlASTHelper.parseJexlQuery(query);
        JexlArgumentDescriptor argDesc = new GeoFunctionsDescriptor().getArgumentDescriptor((ASTFunctionNode) node.jjtGetChild(0).jjtGetChild(0));
        JexlNode queryNode = argDesc.getIndexQuery(null, null, null, null);
        Assert.assertEquals(
                        "(((LON_FIELD >= '170.0' && LON_FIELD <= '180') && (LAT_FIELD >= '40.0' && LAT_FIELD <= '50.0')) && ((LON_FIELD >= '-180' && LON_FIELD <= '-170.0') && (LAT_FIELD >= '40.0' && LAT_FIELD <= '50.0')))",
                        JexlStringBuildingVisitor.buildQuery(queryNode));
    }
}
