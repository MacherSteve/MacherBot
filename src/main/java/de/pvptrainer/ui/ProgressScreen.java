package de.pvptrainer.ui;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.progress.DrillHistory;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.progress.TrainingProgress;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.session.TrainingStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class ProgressScreen extends TrainerPanel {
    private static final int CANVAS = 0xff201e2a, SURFACE = 0xff2a2735, GRID = 0xff484052;
    private static final int MINT = 0xff83e0b7, CORAL = 0xffff9098, AMBER = 0xffffc675, NEUTRAL = 0xffc1b7cc;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault());
    private static final int[] LIMITS = {10, 30, Integer.MAX_VALUE};
    private String setup;
    private String setupLabel;
    private List<String> setups = List.of();
    private List<TrainingProgress.Run> runs = List.of(), visible = List.of(), leaders = List.of();
    private boolean details;
    private int range, focus, page, perPage, cardsY, chartY, footY, plotLeft, plotRight, plotTop, plotBottom;
    private int best, wins, average, meanDelta, oldSize;
    private double median;
    private int mouseX, mouseY, initialMouseX, initialMouseY;
    private boolean firstFrame, pointerMoved;
    private int[] low = {}, high = {};

    ProgressScreen(TrainingScreen parent, TrainingSettings.Mode mode) { super(parent, "Analytics", mode); }
    @Override int preferredWidth() { return 520; }
    @Override int preferredHeight() { return 292; }

    @Override void build() {
        firstFrame=true;pointerMoved=false;
        action(right-106,top+5,84,16,"Bot-Analyse",Style.NORMAL,
            ()->open(new BotAnalysisScreen(this, mode, setup))).tip("Reaktionszeiten und Aktionsfolgen des Bots");
        LinkedHashMap<String,List<TrainingProgress.Run>> groups = new LinkedHashMap<>(16, .75f, true);
        for (var run : TrainingProgress.forScreen(minecraft)) groups.computeIfAbsent(run.setup(), k -> new ArrayList<>()).add(run);
        setups = new ArrayList<>(groups.keySet()).reversed();
        if (setups.isEmpty()) { subtitle = "Lokal"; return; }
        if (!groups.containsKey(setup)) {
            Drill drill = TrainerClient.options(mode).drill();
            String difficulty = TrainerClient.settings(mode).difficultyLabel(drill);
            String active = TrainerClient.status().seriesKey();
            setup = null;
            for (String key : setups) {
                var row = groups.get(key).getLast();
                if (row.mode().equals(mode.name()) && row.drill().equals(drill.name()) && row.difficulty().equals(difficulty)) {
                    if (setup == null || key.equals(active)) setup = key;
                }
            }
            if (setup == null) setup = setups.getFirst();
            focus = groups.get(setup).size() - 1;
        }
        runs = groups.get(setup);
        focus = Math.clamp(focus, 0, runs.size()-1);
        var last = runs.getLast();
        String drill = last.drill();
        try { drill = Drill.valueOf(drill).label(); } catch (IllegalArgumentException ignored) {}
        setupLabel = last.mode() + "  /  " + drill;
        for (var technique : List.of(Drill.TOTEM_POPS, Drill.STUN_SLAM))
            if (setup.endsWith("/" + technique.name())) setupLabel += "  /  " + technique.label();
        if (!last.difficulty().isEmpty()) setupLabel += "  ·  " + last.difficulty();
        if (last.tickRate() != 20) setupLabel += "  ·  " + last.tickRate() + " TPS";
        long variants=groups.values().stream().map(List::getLast).filter(row ->
            last.mode().equals(row.mode()) && last.drill().equals(row.drill()) && last.difficulty().equals(row.difficulty())).count();
        if(variants>1) setupLabel += "  ·  "+setup.substring(0,Math.min(6,setup.length()));
        var owner = minecraft.player != null ? minecraft.player.getUUID() : minecraft.getUser().getProfileId();
        if (last.drill().equals(Drill.OBI_TRAP.name()) && !setup.contains("/") && !setup.endsWith(DrillHistory.ENCLOSED_TRAP)) {
            subtitle = "Archiv · frühere Trap-Regeln";
        } else if (last.drill().equals(Drill.OBI_TRAP.name()) && !setup.contains("/")) {
            var best = runs.stream().filter(TrainingProgress.Run::won).mapToDouble(TrainingProgress.Run::seconds).min();
            subtitle = best.isPresent() ? "Bestzeit " + TrainingStatus.ObiTimer.format(best.getAsDouble()) : "Noch keine Bestzeit";
        } else subtitle = TrainingProgress.streak(owner, setup).label();
        int ordinal = setups.indexOf(setup);
        int actionWidth = inner < 330 ? 65 : 89;
        var leftArrow = action(x,y,18,16,"‹",Style.NORMAL,()->selectSetup(ordinal-1));
        leftArrow.active = ordinal > 0;
        leftArrow.tip("Vorherige Trainingskonfiguration");
        var rightArrow = action(right-actionWidth-21,y,18,16,"›",Style.NORMAL,()->selectSetup(ordinal+1));
        rightArrow.active = ordinal+1 < setups.size();
        rightArrow.tip("Nächste Trainingskonfiguration");
        action(right-actionWidth,y,actionWidth,16,details ? "Übersicht" : "Rundendetails",Style.NORMAL,()-> {
            details = !details;
            if (details) { focus = runs.size()-1; page = 0; }
            refresh();
        });
        if (details) { buildDetails(); return; }
        int first = Math.max(0, runs.size()-LIMITS[range]);
        visible = runs.subList(first, runs.size());
        wins = (int)visible.stream().filter(TrainingProgress.Run::won).count();
        average = mean(visible);
        int previous = Math.max(0, first-visible.size());
        oldSize = first-previous;
        meanDelta = oldSize == 0 ? 0 : average-mean(runs.subList(previous,first));
        var times = visible.stream().filter(TrainingProgress.Run::won).mapToDouble(TrainingProgress.Run::seconds).sorted().toArray();
        median = times.length == 0 ? Double.NaN : (times[(times.length-1)/2] + times[times.length/2]) / 2;
        leaders = runs.stream().filter(TrainingProgress.Run::won)
            .sorted(Comparator.comparingInt(TrainingProgress.Run::score).reversed()).limit(3).toList();
        best = leaders.isEmpty() ? 0 : leaders.getFirst().score();

        cardsY = y+20;
        chartY = cardsY + (bottom - cardsY < 175 ? 42 : 52);
        int footer = inner < 360 ? 46 : 53;
        footY = Math.max(chartY+46, bottom-footer);
        plotLeft=x+32; plotRight=right-10; plotTop=chartY+23; plotBottom=footY-13;
        buildEnvelope();
        String[] labels = {"10", "30", "Alle"};
        for (int i=0;i<LIMITS.length;i++) {
            final int idx=i;
            action(right-84+i*27,chartY+3,25,14,labels[i],range==i?Style.SELECTED:Style.NORMAL,()->{range=idx;refresh();})
                .tip(i==2?"Alle Versuche":"Letzte "+LIMITS[i]+" Versuche");
        }
    }

    private void buildEnvelope() {
        int columns = Math.min(visible.size(), Math.max(1,plotRight-plotLeft));
        low = new int[columns]; high = new int[columns];
        Arrays.fill(low, Integer.MAX_VALUE);
        for (int i=0;i<visible.size();i++) {
            int col = (int)((long)i * columns / visible.size());
            int score = visible.get(i).score();
            low[col]=Math.min(low[col],score);
            high[col]=Math.max(high[col],score);
        }
    }

    private void selectSetup(int idx) {
        setup=setups.get(idx); details=false; focus=0; page=0; refresh();
    }

    static int mean(List<TrainingProgress.Run> rows) {
        return (int)Math.round(rows.stream().mapToInt(TrainingProgress.Run::score).average().orElse(0));
    }
    private static String sec(double s) { return String.format(Locale.GERMAN,"%.2f s",s); }
    private static String date(TrainingProgress.Run r) { return r.time()==0 ? "" : DATE.format(Instant.ofEpochMilli(r.time())); }

    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        if(firstFrame){initialMouseX=mx;initialMouseY=my;firstFrame=false;}
        if(Math.abs(mx-initialMouseX)+Math.abs(my-initialMouseY)>2)pointerMoved=true;
        mouseX=mx; mouseY=my; super.extractRenderState(g,mx,my,delta);
    }

    @Override void content(GuiGraphicsExtractor g) {
        if (setups.isEmpty()) {
            g.fill(x,y,right,Math.min(bottom,y+48),CANVAS);
            line(g,"Dein erster Versuch",x+9,y+9,inner-18,TEXT);
            line(g,"Nach einer gewerteten Runde erscheint hier dein Verlauf.",x+9,y+26,inner-18,NEUTRAL);
            return;
        }
        int actionWidth = inner < 330 ? 65 : 89;
        line(g,setupLabel,x+23,y+4,inner-actionWidth-49,TEXT);
        if(mouseX>=x+22 && mouseX<right-actionWidth-22 && mouseY>=y && mouseY<y+16) {
            String id=setup.substring(0,Math.min(8,setup.length()));
            g.setComponentTooltipForNextFrame(font,List.of(Component.literal(setupLabel),
                Component.literal("Konfiguration "+(setups.indexOf(setup)+1)+" / "+setups.size()+" · "+id)),mouseX,mouseY);
        }
        if(details){ drawDetails(g); return; }
        var last=runs.getLast();
        card(g,0,"Letzter Score",Integer.toString(last.score()),sec(last.seconds())+" · "+(last.won()?"Geschafft":"Fehlversuch"),last.won() ? MINT : CORAL);
        String gapToBest=leaders.isEmpty()?"Noch kein Erfolg":last.score()==best?"Rekord erreicht":"Noch "+(best-last.score())+" Punkte";
        card(g,1,"Bestleistung",best==0 ? "–" : Integer.toString(best),gapToBest,AMBER);
        card(g,2,"Erfolgsquote",Math.round(100.0*wins/visible.size())+" %",wins+" / "+visible.size()+" geschafft",MINT);
        drawChart(g);
        if (footY+12 < bottom) drawSummary(g);
    }

    private void card(GuiGraphicsExtractor g,int col,String name,String value,String detail,int color) {
        int left=col(col,3), width=cw(col,3), h=chartY-cardsY-4;
        g.fill(left,cardsY,left+width,cardsY+h,SURFACE);
        g.fill(left,cardsY,left+2,cardsY+h,color);
        line(g,name,left+6,cardsY+5,width-9,NEUTRAL);
        var pose=g.pose(); pose.pushMatrix(); pose.translate(left+6,cardsY+16);
        pose.scale(2f);
        g.text(font,value,0,0,color,false); pose.popMatrix();
        if(h>=44)line(g,detail,left+6,cardsY+h-11,width-9,NEUTRAL);
    }

    private void drawChart(GuiGraphicsExtractor g) {
        g.fill(x,chartY,right,footY-2,CANVAS);
        line(g,"Score-Verlauf",x+7,chartY+7,inner-100,TEXT);
        if(plotBottom-plotTop<8) return;
        int averageX=x+15+font.width("Score-Verlauf");
        line(g,"Ø "+average,averageX,chartY+7,right-90-averageX,NEUTRAL);
        if(high.length>1) {
            // At most one strip per two GUI pixels, including for multi-year histories.
            for(int xx=plotLeft;xx<plotRight;xx+=2){
                double at=(xx-plotLeft)*(high.length-1.0)/(plotRight-plotLeft);
                int a=(int)at, b=Math.min(a+1,high.length-1);
                double score=high[a]+(high[b]-high[a])*(at-a);
                g.fill(xx,scoreY((int)Math.round(score)),Math.min(xx+2,plotRight),plotBottom,0x1c61b6ad);
            }
        }
        for(int k=0;k<3;k++){
            int mark=2000-k*1000;
            int yy=scoreY(mark);
            line(g,Integer.toString(mark),x+4,yy-3,27,NEUTRAL);
            g.fill(plotLeft,yy,plotRight+1,yy+1,GRID);
        }
        int averageY=scoreY(average);
        for(int xx=plotLeft;xx<plotRight;xx+=7)g.fill(xx,averageY,Math.min(xx+3,plotRight),averageY+1,0x81908aa8);
        int n=high.length, previousX=0,previousY=0;
        for(int col=0;col<n;col++){
            int xx=plotX(col,n), yy=scoreY(high[col]), ly=scoreY(low[col]);
            if(low[col]!=high[col]) g.fill(xx,yy,xx+1,ly+1,0xff748d88);
            if(col>0) segment(g,previousX,previousY,xx,yy);
            if(n<=40 || col==n-1){
                int marker=n<=35?2:1;
                g.fill(xx-marker,yy-marker,xx+marker+1,yy+marker+1,high[col]==0?CORAL:MINT);
            }
            previousX=xx;previousY=yy;
        }
        line(g,"#"+(runs.size()-visible.size()+1),plotLeft,plotBottom+3,40,NEUTRAL);
        String end="#"+runs.size();
        if(visible.size()>1) line(g,end,plotRight-font.width(end),plotBottom+3,font.width(end)+1,NEUTRAL);
        if (pointerMoved && overChart(mouseX,mouseY)) {
            int idx = hoverIndex(mouseX);
            var sample=visible.get(idx);
            int xx=plotX(idx,visible.size()), yy=scoreY(sample.score());
            g.fill(xx,plotTop,xx+1,plotBottom+1,0x80e3e2dc);
            g.fill(xx-2,yy-2,xx+3,yy+3,sample.won()?MINT:CORAL);
            g.setComponentTooltipForNextFrame(font,List.of(
                Component.literal("Versuch "+(runs.size()-visible.size()+idx+1)+" · "+(sample.won()?"Geschafft":"Fehlversuch")),
                Component.literal(sample.score()+" Punkte  ·  "+sec(sample.seconds())),
                Component.literal("Klicken für Rundendetails")),mouseX,mouseY);
        }
    }

    private int scoreY(int score){ return plotBottom-(int)Math.round(score*(plotBottom-plotTop)/2000.0); }
    private int plotX(int i,int n){ return n==1?(plotLeft+plotRight)/2:plotLeft+(int)Math.round(i*(plotRight-plotLeft)/(double)(n-1)); }
    private boolean overChart(double mx,double my){return mx>=plotLeft-2 && mx<=plotRight+2 && my>=plotTop-2 && my<=plotBottom+2;}
    private int hoverIndex(double mx){return Math.clamp((int)Math.round((mx-plotLeft)*(visible.size()-1)/Math.max(1,plotRight-plotLeft)),0,visible.size()-1);}
    private void segment(GuiGraphicsExtractor g,int x1,int y1,int x2,int y2) {
        var pose=g.pose(); pose.pushMatrix(); pose.translate(x1+.5f,y1+.5f);
        pose.rotate((float)Math.atan2(y2-y1,x2-x1));
        g.fill(0,0,(int)Math.ceil(Math.hypot(x2-x1,y2-y1)),1,0xff7ebdba); pose.popMatrix();
    }

    private void drawSummary(GuiGraphicsExtractor g) {
        int cut=x+(inner*54)/100;
        g.fill(x,footY,cut-gap, bottom, SURFACE); g.fill(cut,footY,right,bottom,SURFACE);
        int w=cut-gap-x-10;
        line(g,"Vergleich  ·  "+visible.size()+" Versuche",x+5,footY+4,w,TEXT);
        String trend=oldSize==0?"":"  ("+String.format(Locale.GERMAN,"%+d",meanDelta)+")";
        line(g,"Ø Punkte  "+average+trend,x+5,footY+16,w,NEUTRAL);
        if(footY+35<bottom)line(g,"Median Erfolg  "+(Double.isNaN(median)?"–":sec(median)),x+5,footY+27,w,NEUTRAL);
        if (oldSize>0 && mouseX>=x && mouseX<cut-gap && mouseY>=footY && mouseY<bottom)
            g.setTooltipForNextFrame(Component.literal("Score-Differenz zu den "+oldSize+" vorherigen Versuchen"),mouseX,mouseY);
        line(g,"Persönliche Top 3",cut+5,footY+4,right-cut-10,TEXT);
        for(int i=0;i<leaders.size() && footY+24+10*i<=bottom;i++){
            var row=leaders.get(i);
            line(g,(i+1)+".   "+row.score()+" P.   "+sec(row.seconds()),cut+5,footY+16+i*10,right-cut-10,i==0?AMBER:NEUTRAL);
        }
        if(leaders.isEmpty())line(g,"Noch kein Erfolg",cut+5,footY+16,right-cut-10,NEUTRAL);
    }

    @Override public boolean mouseClicked(MouseButtonEvent ev,boolean twice) {
        if(!details && !visible.isEmpty() && ev.button()==0 && overChart(ev.x(),ev.y())) {
            focus=runs.size()-visible.size()+hoverIndex(ev.x());details=true;page=0;refresh();return true;
        }
        return super.mouseClicked(ev,twice);
    }

    private void buildDetails() {
        int nav=y+21;
        var prev=action(x,nav,21,16,"‹",Style.NORMAL,()->{focus--;page=0;refresh();});
        prev.active=focus>0;prev.tip("Vorheriger Versuch");
        var next=action(right-21,nav,21,16,"›",Style.NORMAL,()->{focus++;page=0;refresh();});
        next.active=focus+1<runs.size();next.tip("Nächster Versuch");
        int eventStart=y+99;
        perPage=Math.max(1,(bottom-17-eventStart)/13);
        int count=runs.get(focus).timeline().size();
        page=Math.clamp(page,0,Math.max(0,(count-1)/perPage));
        if(count>perPage){
            var back=action(x,bottom-14,21,14,"‹",Style.NORMAL,()->{page--;refresh();});
            var more=action(right-21,bottom-14,21,14,"›",Style.NORMAL,()->{page++;refresh();});
            back.active=page>0;more.active=(page+1)*perPage<count;
        }
    }

    private void drawDetails(GuiGraphicsExtractor g) {
        var run=runs.get(focus);
        line(g,"Versuch "+(focus+1)+" / "+runs.size()+"   "+date(run),x+27,y+25,inner-54,TEXT);
        int top=y+41, c=run.won()?MINT:CORAL;
        g.fill(x,top,right,top+38,SURFACE);g.fill(x,top,x+2,top+38,c);
        line(g,(run.won()?"Geschafft":"Fehlversuch")+"    "+run.score()+" P.    "+sec(run.seconds()),x+7,top+6,inner-14,c);
        String why=run.reason().isEmpty()?"Kein Abschlussgrund gespeichert":run.reason();
        line(g,why,x+7,top+22,inner-14,TEXT);
        if(mouseY>=top && mouseY<top+38 && mouseX>=x && mouseX<right && font.width(why)>inner-14)
            g.setTooltipForNextFrame(Component.literal(why),mouseX,mouseY);
        line(g,"Ereignisse",x+2,y+85,inner-4,TEXT);
        if(run.timeline().isEmpty()) {line(g,"Keine Ereignisse aufgezeichnet.",x+2,y+100,inner-4,NEUTRAL);return;}
        int start=page*perPage;
        for(int i=start;i<Math.min(run.timeline().size(),start+perPage);i++) {
            int ty=y+98+(i-start)*13;
            g.fill(x,ty,right,ty+12,(i&1)==0?CANVAS:SURFACE);
            String event=run.timeline().get(i);
            line(g,event,x+5,ty+2,inner-10,NEUTRAL);
            if(mouseY>=ty && mouseY<ty+12 && mouseX>=x && mouseX<right && font.width(event)>inner-10)
                g.setTooltipForNextFrame(Component.literal(event),mouseX,mouseY);
        }
        if(run.timeline().size()>perPage) line(g,"Seite "+(page+1)+" / "+((run.timeline().size()+perPage-1)/perPage),x+27,bottom-11,inner-54,NEUTRAL);
    }
}
