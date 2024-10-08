package com.telerikacademy.web.smartgarageti.controllers.mvc;

import com.telerikacademy.web.smartgarageti.exceptions.AuthenticationException;
import com.telerikacademy.web.smartgarageti.exceptions.DuplicateEntityException;
import com.telerikacademy.web.smartgarageti.exceptions.EntityNotFoundException;
import com.telerikacademy.web.smartgarageti.exceptions.UnauthorizedOperationException;
import com.telerikacademy.web.smartgarageti.helpers.AuthenticationHelper;
import com.telerikacademy.web.smartgarageti.helpers.MapperHelper;
import com.telerikacademy.web.smartgarageti.models.*;
import com.telerikacademy.web.smartgarageti.models.dto.ClientCarDtoMvc;
import com.telerikacademy.web.smartgarageti.models.dto.ClientCarUpdateDto;
import com.telerikacademy.web.smartgarageti.services.contracts.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.telerikacademy.web.smartgarageti.controllers.mvc.OrderMvcController.EMPLOYEE;

@Controller
@RequestMapping("/ti/client-cars")
public class ClientCarsMvcController {
    private final AuthenticationHelper authenticationHelper;
    private final ClientCarService clientCarService;
    private final BrandService brandService;
    private final UserService userService;
    private final MapperHelper mapperHelper;
    private final OrderService orderService;
    private final CarServiceLogService carServiceLogService;
    private final RepairServiceService repairServiceService;

    @Autowired
    public ClientCarsMvcController(AuthenticationHelper authenticationHelper, ClientCarService clientCarService, BrandService brandService, UserService userService, MapperHelper mapperHelper, OrderService orderService, CarServiceLogService carServiceLogService, RepairServiceService repairServiceService) {
        this.authenticationHelper = authenticationHelper;
        this.clientCarService = clientCarService;
        this.brandService = brandService;
        this.userService = userService;
        this.mapperHelper = mapperHelper;
        this.orderService = orderService;
        this.carServiceLogService = carServiceLogService;
        this.repairServiceService = repairServiceService;
    }

    @ModelAttribute("isAuthenticated")
    public boolean populateIsAuthenticated(HttpSession session) {
        return session.getAttribute("currentUser") != null;
    }

    @ModelAttribute("loggedInUser")
    public User populateUser(HttpSession session) {
        if (session.getAttribute("currentUser") != null) {
            return authenticationHelper.tryGetUserFromSession(session);
        }
        return null;
    }

    @ModelAttribute("isEmployee")
    public boolean populateIsEmployee(HttpSession session) {
        if (session.getAttribute("currentUser") != null) {
            User user = authenticationHelper.tryGetUserFromSession(session);
            return user.getRole().getName().equals("Employee");
        }
        return false;
    }

    @ModelAttribute("allVehicleBrands")
    public List<Brand> populateAllVehicleBrands() {
        return brandService.findAllBrands();
    }

    @GetMapping("/usernames")
    @ResponseBody
    public List<String> findUsernames(@RequestParam("term") String term) {
        List<String> usernames = userService.findUsernamesByTerm(term);
        System.out.println("Usernames found: " + usernames);
        return usernames;
    }

    @GetMapping("/repairservices")
    @ResponseBody
    public List<String> findRepairServices(@RequestParam("term") String term) {
        List<String> repairServices = repairServiceService.findRepairServicesByTerm(term);
        System.out.println("Repair Services found: " + repairServices);
        return repairServices;
    }

    @GetMapping
    public String listClientCars(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "searchTerm", required = false) String searchTerm,
            @RequestParam(value = "sortBy", defaultValue = "") String sortBy,
            @RequestParam(value = "order", defaultValue = "asc") String order,
            Model model, HttpSession session) {

        try {
            User user = authenticationHelper.tryGetUserFromSession(session);

            if (user.getRole().getId() != EMPLOYEE) {
                model.addAttribute("errorMessage", "You are not employee and can't see this page!");
                return "403AccessDenied";
            }
        } catch (AuthenticationException e) {
            return "redirect: /ti/auth/login";
        }
        Pageable pageable = PageRequest.of(page, size, Sort.Direction.fromString(order), sortBy.isEmpty() ? "owner.username" : sortBy);

        Page<ClientCar> clientCarPage;

        if (searchTerm == null || searchTerm.isEmpty()) {
            clientCarPage = clientCarService.getAllClientCars(pageable);
        } else {
            clientCarPage = clientCarService.filterAndSortClientCarsByOwner(searchTerm, pageable);
        }

        model.addAttribute("clientCars", clientCarPage.getContent());
        model.addAttribute("totalPages", clientCarPage.getTotalPages());
        model.addAttribute("currentPage", page);
        model.addAttribute("searchTerm", searchTerm);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("order", order);
        model.addAttribute("clientCarDtoMvc", new ClientCarDtoMvc());

        return "ClientCars";
    }

    @PostMapping
    public String addClientCar(
            @Valid @ModelAttribute("clientCarDtoMvc") ClientCarDtoMvc clientCarDtoMvc,
            BindingResult bindingResult,
            Model model,
            HttpSession session) {

        int currentPage = 0;
        int pageSize = 10;
        Page<ClientCar> clientCarsPage = clientCarService.getAllClientCars(PageRequest.of(currentPage, pageSize));

        int totalPages = clientCarsPage.getTotalPages();

        model.addAttribute("clientCars", clientCarsPage.getContent());
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("currentPage", currentPage);

        if (bindingResult.hasErrors()) {
            return "ClientCars";
        }

        try {
            User loggedInUser = authenticationHelper.tryGetUserFromSession(session);
            User userToAddCar = userService.getByUsername(clientCarDtoMvc.getOwner());
            Brand brand = brandService.findBrandByName(clientCarDtoMvc.getBrandName());

            ClientCar newClientCar = mapperHelper.createClientCarFromDto(clientCarDtoMvc, userToAddCar, brand, loggedInUser);
            clientCarService.createClientCar(newClientCar);
            return "redirect:/ti/client-cars";
        } catch (EntityNotFoundException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (DuplicateEntityException e) {
            bindingResult.rejectValue("vin", "error.clientCarDtoMvc", e.getMessage());
            model.addAttribute("clientCars", clientCarsPage.getContent());
            model.addAttribute("totalPages", totalPages);
            model.addAttribute("currentPage", currentPage);
            return "ClientCars";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage()); //dd
            return "404";
        } catch (AuthenticationException e) {
            return "redirect: /ti/auth/login";
        }
    }

    @GetMapping("/edit/{id}")
    public String editClientCar(@PathVariable int id, Model model, HttpSession session) {
        try {
            User loggedInUser = authenticationHelper.tryGetUserFromSession(session);
            ClientCar clientCar = clientCarService.getClientCarById(id);
            ClientCarUpdateDto clientCarUpdateDto = new ClientCarUpdateDto();
            clientCarUpdateDto.setVin(clientCar.getVin());
            clientCarUpdateDto.setLicense_plate(clientCar.getLicensePlate());
            model.addAttribute("clientCar", clientCar);
            model.addAttribute("clientCarUpdateDto", clientCarUpdateDto);
            return "ClientCars";
        } catch (EntityNotFoundException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (AuthenticationException e) {
            return "redirect: /ti/auth/login";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "403AccessDenied";
        }
    }

    @PostMapping("/edit/{id}")
    public String saveClientCar(
            @PathVariable int id,
            @Valid @ModelAttribute("clientCarUpdateDto") ClientCarUpdateDto clientCarUpdateDto,
            BindingResult bindingResult,
            Model model, HttpSession session) {

        if (bindingResult.hasErrors()) {
            ClientCar existingCar = clientCarService.getClientCarById(id);
            model.addAttribute("clientCar", existingCar);
            model.addAttribute("clientCarUpdateDto", clientCarUpdateDto);
            model.addAttribute("clientCars", clientCarService.getAllClientCars(PageRequest.of(0, 10)).getContent());
            model.addAttribute("totalPages", 1);
            model.addAttribute("currentPage", 0);
            return "ClientCars";
        }

        try {
            User loggedInUser = authenticationHelper.tryGetUserFromSession(session);
            ClientCar updatedCar = mapperHelper.updateClientCarFromDto(clientCarUpdateDto, id);
            clientCarService.updateClientCar(updatedCar, loggedInUser);
            return "redirect:/ti/client-cars";
        } catch (DuplicateEntityException e) {
            ClientCar existingCar = clientCarService.getClientCarById(id);
            model.addAttribute("clientCar", existingCar);
            bindingResult.rejectValue("vin", "error.clientCarUpdateDto", e.getMessage());
            model.addAttribute("clientCarUpdateDto", clientCarUpdateDto);
            model.addAttribute("clientCars", clientCarService.getAllClientCars(PageRequest.of(0, 10)).getContent());
            model.addAttribute("totalPages", 1);
            model.addAttribute("currentPage", 0);
            return "ClientCars";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "403AccessDenied";
        } catch (AuthenticationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "redirect: /ti/auth/login";
        }
    }

    @GetMapping("/{clientCarId}/delete")
    public String deleteClientCar(@PathVariable int clientCarId, Model model, HttpSession session) {
        User user;
        try {
            user = authenticationHelper.tryGetUserFromSession(session);
        } catch (AuthenticationException e) {
            return "redirect:/ti/auth/login";
        }

        try {
            ClientCar clientCar = clientCarService.getClientCarById(clientCarId);
            clientCarService.deleteClientCar(clientCarId, user);
            return "redirect:/ti/client-cars";
        } catch (EntityNotFoundException | IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "403AccessDenied";
        }
    }

    @GetMapping("/{clientCarId}/services")
    public String showSpecificClientCarServiceLogs(@PathVariable int clientCarId, Model model, HttpSession session) {
        try {
            User user = authenticationHelper.tryGetUserFromSession(session);
            ClientCar clientCar = clientCarService.getClientCarById(clientCarId);
            List<CarServiceLog> carServiceLogs = carServiceLogService.findNotStartedOrdersByClientCarId(clientCarId, user);

            List<String> allRepairServices = repairServiceService.findRepairServicesByTerm("");

            model.addAttribute("clientCar", clientCar);
            model.addAttribute("clientCarOrders", carServiceLogs);
            model.addAttribute("repairServices", allRepairServices);

        } catch (EntityNotFoundException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "403AccessDenied";
        } catch (AuthenticationException e) {
            return "redirect: /ti/auth/login";
        }

        return "AddServiceToClientCar";
    }

    @PostMapping("/{clientCarId}/services")
    public String addServiceToClientCar(
            @PathVariable int clientCarId,
            @RequestParam("repairService") String repairServiceName,
            HttpSession session,
            Model model) {

        try {
            User user = authenticationHelper.tryGetUserFromSession(session);

            RepairService repairService = repairServiceService.findServiceByName(repairServiceName);

            ClientCar clientCar = clientCarService.getClientCarById(clientCarId);
            if (clientCar == null) {
                model.addAttribute("errorMessage", "Car was not found");
                return "AddServiceToClientCar";
            }

            List<CarServiceLog> existingServices = carServiceLogService.findNotStartedOrdersByClientCarId(clientCarId, user);

            model.addAttribute("clientCarOrders", existingServices);

            boolean serviceAlreadyAdded = existingServices.stream()
                    .anyMatch(service -> service.getService().getName().equals(repairServiceName));

            if (serviceAlreadyAdded) {
                model.addAttribute("errorMessage", "This service is already in this order.");
                model.addAttribute("clientCar", clientCar);
                return "AddServiceToClientCar";
            }

            carServiceLogService.addServiceToOrder(clientCarId, repairService, user);

            return "redirect:/ti/client-cars/" + clientCarId + "/services";
        } catch (EntityNotFoundException | UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (DuplicateEntityException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "AddServiceToClientCar";
        } catch (AuthenticationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "redirect: /ti/auth/login";
        }
    }

    @PostMapping("/{clientCarId}/services/delete")
    public String deleteServiceLog(
            @PathVariable int clientCarId,
            @RequestParam("serviceLogId") int serviceLogId,
            Model model,
            HttpSession session) {

        try {
            User user = authenticationHelper.tryGetUserFromSession(session);
            carServiceLogService.deleteServiceFromOrder(serviceLogId, clientCarId, user);
            return "redirect:/ti/client-cars/" + clientCarId + "/services";
        } catch (EntityNotFoundException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "404";
        } catch (UnauthorizedOperationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "403AccessDenied";
        } catch (AuthenticationException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "redirect: /ti/auth/login";
        }
    }
}
