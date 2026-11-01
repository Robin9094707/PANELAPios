package eu.simplexsmp.adminsuite;
import java.util.*;
import java.time.Duration;
import java.util.function.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
/** Paper dialogs only for text prompts. Information and action menus keep their existing presentation. */
final class NativeTextDialogs {
    private static final Map<UUID,UUID> generations=new java.util.concurrent.ConcurrentHashMap<>();
    static boolean prompt(SimplexAdminSuite plugin,Player p,String title,String initial,BooleanSupplier allowed,Consumer<String> action){
        if(plugin.isBedrockClient(p))return false;
        try{
            UUID owner=p.getUniqueId(),generation=UUID.randomUUID();generations.put(owner,generation);String previous=initial==null?"":initial;
            var callback=DialogAction.customClick((view,audience)->{
                if(!(audience instanceof Player sender)||!sender.getUniqueId().equals(owner)||!generations.remove(owner,generation))return;
                String value=view.getText("value");plugin.runAdminTask(()->{if(p.isOnline()&&allowed.getAsBoolean()&&value!=null&&value.length()<=4000)action.accept(value);});
            },ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
            Dialog dialog=Dialog.create(builder->builder.empty().base(DialogBase.builder(Component.text(title)).inputs(List.of(DialogInput.text("value",Component.text(plugin.playerText(p,"Neuer Wert","New value"))).initial(previous.substring(0,Math.min(4000,previous.length()))).maxLength(4000).width(300).build())).build()).type(DialogType.confirmation(
                ActionButton.builder(Component.text(plugin.playerText(p,"Übernehmen","Apply"))).action(callback).build(),
                ActionButton.builder(Component.text(plugin.playerText(p,"Abbrechen","Cancel"))).build())));
            p.closeInventory();p.showDialog(dialog);org.bukkit.Bukkit.getScheduler().runTaskLater(plugin,()->generations.remove(owner,generation),6000);return true;
        }catch(LinkageError|RuntimeException unsupported){generations.remove(p.getUniqueId());return false;}
    }
}
