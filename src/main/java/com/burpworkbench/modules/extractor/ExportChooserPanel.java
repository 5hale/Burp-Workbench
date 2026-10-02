package com.burpworkbench.modules.extractor;

import com.burpworkbench.modules.extractor.filter.FilterSettings;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Output folder chooser with independent Beautify and Filter export options. */
final class ExportChooserPanel extends JPanel {
    final JFileChooser chooser=new JFileChooser();
    final JCheckBox beautify=new JCheckBox("Beautify",true),filter=new JCheckBox("Filter",false);
    final JButton save=new JButton("Save"),cancel=new JButton("Cancel");
    final JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
    private final Supplier<FilterSettings> rules;
    record Choice(Path root,ExportOptions options,FilterSettings filterSettings){}
    ExportChooserPanel(Supplier<FilterSettings> rules){
        super(new BorderLayout());this.rules=rules;
        chooser.setDialogTitle("Choose Extractor output folder");chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);chooser.setAcceptAllFileFilterUsed(false);chooser.setControlButtonsAreShown(false);
        beautify.setToolTipText("Beautify JS and JSON responses before saving.");filter.setToolTipText("Apply rules configured in the Extractor tab before saving.");
        options.setBorder(BorderFactory.createEmptyBorder(7,10,10,10));options.add(beautify);options.add(Box.createHorizontalStrut(12));options.add(filter);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));buttons.setBorder(BorderFactory.createEmptyBorder(7,10,10,10));buttons.add(save);buttons.add(cancel);
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(options,BorderLayout.WEST);bottom.add(buttons,BorderLayout.EAST);
        add(chooser,BorderLayout.CENTER);add(bottom,BorderLayout.SOUTH);
    }
    Choice choice(){File file=chooser.getSelectedFile();if(file==null)file=chooser.getCurrentDirectory();if(file==null)return null;return new Choice(file.toPath(),ExportOptions.defaults().withBeautify(beautify.isSelected()),filter.isSelected()?rules.get():null);}
}
