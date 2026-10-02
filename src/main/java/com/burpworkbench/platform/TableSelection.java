package com.burpworkbench.platform;

import javax.swing.JTable;
import java.util.Arrays;
import java.util.function.BiConsumer;

/** Stable model-order batch operations, including sorted table views. */
public final class TableSelection {
    private TableSelection(){}
    public static int[] rows(JTable table){return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).sorted().toArray();}
    public static int lead(JTable table){int row=table.getSelectionModel().getLeadSelectionIndex();if(row<0||row>=table.getRowCount()||!table.isRowSelected(row))row=table.getSelectedRow();return row<0?-1:table.convertRowIndexToModel(row);}
    public static void select(JTable table,int[] rows){
        var selection=table.getSelectionModel();selection.setValueIsAdjusting(true);
        try{table.clearSelection();for(int row:rows){int view=table.convertRowIndexToView(row);if(view>=0)table.addRowSelectionInterval(view,view);}}
        finally{selection.setValueIsAdjusting(false);}
    }
    public static int[] move(int size,int[] rows,int direction,BiConsumer<Integer,Integer> swap){
        boolean[] selected=new boolean[size];for(int row:rows)selected[row]=true;
        for(int n=0;n<size;n++){int i=direction<0?n:size-1-n,j=i+direction;if(selected[i]&&j>=0&&j<size&&!selected[j]){swap.accept(i,j);selected[j]=true;selected[i]=false;}}
        int[] result=new int[rows.length];int n=0;for(int i=0;i<size;i++)if(selected[i])result[n++]=i;return result;
    }
}
